package xiangjiang.backend.fu

import chisel3._
import chisel3.util._
import xiangjiang._

/** Physical 8x8 systolic array with committed and working state.
  * A always advances west-to-east and B advances north-to-south. BF16 uses
  * output-stationary (OS); INT8 selects OS or weight-stationary (WS) while
  * reusing the same PE multipliers, adders, and registered neighbour links.
  */
class MatrixArrayIO extends Bundle {
  val loadBeat = Input(Valid(new Bundle {
    val buffer = UInt(2.W)
    val beat = UInt(3.W)
    val data = UInt(512.W)
  }))
  val storeBeat = Input(UInt(3.W))
  val storeData = Output(UInt(512.W))
  val start = Flipped(Decoupled(new Bundle { val op = MatOp() }))
  val mode = Input(Bool())
  val publish = Input(Valid(UInt(2.W)))
  val validBuffers = Output(Vec(3, Bool()))
  val busy = Output(Bool())
  val flush = Input(Bool())
}

class MatrixArray(bf16: Boolean) extends Module {
  val io = IO(new MatrixArrayIO)
  private val width = if (bf16) 16 else 8
  private val Seq(idle, prepare, loadWeights, computeOS, computeWS, captureWS,
    elementwise, complete) = Enum(8)
  private val state = RegInit(idle)
  private val op = Reg(MatOp())
  private val ws = RegInit(false.B)
  private val phase = RegInit(0.U(7.W))
  // Power-of-two cadence keeps edge scheduling to bit slices, not divide/modulo logic.
  private val cadence = if (bf16) 1 << log2Ceil(VectorFloat.fma32PipelineStages + 1) else 1
  // Neighbour links still advance every clock. Valid bubbles space successive
  // k terms far enough apart for the pipelined accumulator feedback.
  private val lastPair = 14 + 7 * cadence
  private val lastCompute = lastPair + (if (bf16) VectorFloat.fma32PipelineStages else 0)

  // Architectural state is changed only by publish. Loads and computations
  // first write staging so a flush cannot expose speculative matrix state.
  private val a = RegInit(VecInit(Seq.fill(64)(0.U(width.W))))
  private val b = RegInit(VecInit(Seq.fill(64)(0.U(width.W))))
  private val acc = RegInit(VecInit(Seq.fill(64)(0.U(32.W))))
  private val valid = RegInit(VecInit(Seq.fill(3)(false.B)))
  private val staging = RegInit(VecInit(Seq.fill(64)(0.U(32.W))))
  // Store conversion is a physical writeback boundary.  Do not leave the
  // FP32 -> BF16 rounder and the 32:1 beat mux in the MSTORE timing path.
  // The architectural accumulator remains FP32; this register is only a
  // cached, IEEE-RNE BF16 representation of it.
  private val narrowAcc = RegInit(VecInit(Seq.fill(64)(0.U(16.W))))
  private val pes = Seq.tabulate(8, 8) { (_, _) =>
    Module(new SystolicPE(width, bf16))
  }

  io.validBuffers := valid
  io.busy := state =/= idle
  io.start.ready := state === idle && !io.flush

  when(io.loadBeat.valid && !io.flush) {
    for (e <- 0 until 64) {
      val inputPerBeat = 512 / width
      val inputValue = io.loadBeat.bits.data((e % inputPerBeat + 1) * width - 1,
        (e % inputPerBeat) * width)
      if (bf16) {
        when(io.loadBeat.bits.beat === (e / 32).U) {
          staging(e) := Mux(io.loadBeat.bits.buffer === 2.U,
            VectorFloat.widen(inputValue), inputValue)
        }
      } else {
        when(io.loadBeat.bits.buffer =/= 2.U && io.loadBeat.bits.beat === 0.U) {
          staging(e) := inputValue
        }
        when(io.loadBeat.bits.buffer === 2.U && io.loadBeat.bits.beat === (e / 16).U) {
          staging(e) := io.loadBeat.bits.data((e % 16 + 1) * 32 - 1,
            (e % 16) * 32)
        }
      }
    }
  }

  if (bf16) {
    io.storeData := VecInit((0 until 32).map(i =>
      narrowAcc(Cat(io.storeBeat(0), i.U(5.W))))).asUInt
  } else {
    io.storeData := VecInit((0 until 16).map(i =>
      acc(Cat(io.storeBeat(1, 0), i.U(4.W))))).asUInt
  }

  // Defaults. Every matrix product below uses only nearest-neighbour PE links.
  for ((row, r) <- pes.zipWithIndex; (pe, c) <- row.zipWithIndex) {
    pe.io.aIn := 0.U
    pe.io.bIn := 0.U
    pe.io.aInValid := false.B
    pe.io.bInValid := false.B
    pe.io.tagIn := 0.U
    pe.io.moveA := false.B
    pe.io.moveB := false.B
    pe.io.clearPipe := io.flush || state === prepare
    pe.io.sumIn := 0.U
    pe.io.sumInValid := false.B
    pe.io.macEnable := false.B
    pe.io.useAccumulator := false.B
    pe.io.useStoredB := false.B
    pe.io.loadAccumulator := state === prepare
    pe.io.accumulatorIn := Mux(op === MatOp.mmacc, acc(r * 8 + c), 0.U)
  }

  // Output-stationary wavefront. A(i,k) enters at i+k*cadence and B(k,j)
  // at j+k*cadence. Registered links align equal k at i+j+k*cadence.
  // INT8 cadence is one; BF16 bubbles respect the FMA feedback latency.
  for (r <- 0 until 8; c <- 0 until 8) {
    val pe = pes(r)(c)
    if (c == 0) {
      val offset = phase - r.U
      val k = (offset / cadence.U)(2, 0)
      pe.io.aIn := a(Cat(r.U(3.W), k))
      pe.io.aInValid := state === computeOS && phase >= r.U &&
        phase <= (r + 7 * cadence).U && (offset % cadence.U) === 0.U
    } else {
      pe.io.aIn := pes(r)(c - 1).io.aOut
      pe.io.aInValid := pes(r)(c - 1).io.aOutValid
    }
    if (r == 0) {
      val offset = phase - c.U
      val k = (offset / cadence.U)(2, 0)
      pe.io.bIn := b(Cat(k, c.U(3.W)))
      pe.io.bInValid := state === computeOS && phase >= c.U &&
        phase <= (c + 7 * cadence).U && (offset % cadence.U) === 0.U
    } else {
      pe.io.bIn := pes(r - 1)(c).io.bOut
      pe.io.bInValid := pes(r - 1)(c).io.bOutValid
    }
    when(state === computeOS) {
      pe.io.moveA := true.B
      pe.io.moveB := true.B
      pe.io.macEnable := pe.io.aInValid && pe.io.bInValid
      pe.io.useAccumulator := true.B
    }
  }

  if (!bf16) {
    // WS preload: send B rows in reverse order from the north. After eight
    // registered shifts PE(k,j) owns B(k,j). No separate weight memory or MAC
    // array is used.
    for (r <- 0 until 8; c <- 0 until 8) {
      val pe = pes(r)(c)
      when(state === loadWeights) {
        if (r == 0) {
          pe.io.bIn := b(Cat(7.U(3.W) - phase(2, 0), c.U(3.W)))
          pe.io.bInValid := true.B
        } else {
          pe.io.bIn := pes(r - 1)(c).io.bOut
          pe.io.bInValid := pes(r - 1)(c).io.bOutValid
        }
        pe.io.moveB := true.B
      }

      // For output row i, A(i,k) is injected into PE row k at cycle i+k.
      // A moves east while the registered partial sum moves south. The bottom
      // row emits C(i,j); tags preserve i while columns have different skew.
      when(state === computeWS) {
        if (c == 0) {
          val outputRow = (phase - r.U)(2, 0)
          pe.io.aIn := a(Cat(outputRow, r.U(3.W)))
          pe.io.aInValid := phase >= r.U && phase < (r + 8).U
          pe.io.tagIn := outputRow
        } else {
          pe.io.aIn := pes(r)(c - 1).io.aOut
          pe.io.aInValid := pes(r)(c - 1).io.aOutValid
          pe.io.tagIn := pes(r)(c - 1).io.tagOut
        }
        if (r == 0) {
          pe.io.sumIn := Mux(op === MatOp.mmacc,
            acc(Cat(pe.io.tagIn, c.U(3.W))), 0.U)
          pe.io.sumInValid := pe.io.aInValid
        } else {
          pe.io.sumIn := pes(r - 1)(c).io.sumOut
          pe.io.sumInValid := pes(r - 1)(c).io.sumOutValid
        }
        pe.io.moveA := true.B
        pe.io.macEnable := pe.io.aInValid && pe.io.sumInValid
        pe.io.useStoredB := true.B
      }
    }
  }

  // MEW reuses each PE's one MAC with a zero addend. MTRANS is a data move,
  // not an alternative matrix-multiplication datapath or a reduction tree.
  when(state === elementwise) {
    for (r <- 0 until 8; c <- 0 until 8) {
      val e = r * 8 + c
      val pe = pes(r)(c)
      when(op === MatOp.mew) {
        pe.io.aIn := a(e)
        pe.io.bIn := b(e)
        pe.io.aInValid := true.B
        pe.io.bInValid := true.B
        pe.io.macEnable := phase === 0.U
        pe.io.useAccumulator := true.B
      }.otherwise {
        staging(e) := (if (bf16) VectorFloat.widen(a(c * 8 + r))
          else a(c * 8 + r).asSInt.pad(32).asUInt)
      }
    }
  }

  // WS results leave the bottom row over several skewed cycles. Capture each
  // registered result by its propagated output-row tag.
  when(state === computeWS || state === captureWS) {
    for (c <- 0 until 8) {
      val bottom = pes(7)(c)
      when(bottom.io.sumOutValid) {
        staging(Cat(bottom.io.tagOut, c.U(3.W))) := bottom.io.sumOut
      }
    }
  }

  // OS and MEW results are held in the PE-local accumulator until copied.
  when(state === complete && !io.flush &&
    (op === MatOp.mmul || op === MatOp.mmacc || op === MatOp.mew) && !ws) {
    for (r <- 0 until 8; c <- 0 until 8) {
      staging(r * 8 + c) := pes(r)(c).io.accumulator
    }
  }

  when(io.start.fire) {
    op := io.start.bits.op
    ws := (if (bf16) false.B else io.mode) &&
      (io.start.bits.op === MatOp.mmul || io.start.bits.op === MatOp.mmacc)
    phase := 0.U
    state := prepare
  }
  when(state === prepare) {
    phase := 0.U
    state := Mux(op === MatOp.mmul || op === MatOp.mmacc,
      Mux(ws, loadWeights, computeOS), elementwise)
  }
  when(state === loadWeights) {
    when(phase === 7.U) { phase := 0.U; state := computeWS }
      .otherwise { phase := phase + 1.U }
  }
  when(state === computeOS) {
    when(phase === lastCompute.U) { state := complete }
      .otherwise { phase := phase + 1.U }
  }
  when(state === computeWS) {
    when(phase === 21.U) { state := captureWS }
      .otherwise { phase := phase + 1.U }
  }
  when(state === captureWS) { state := complete }
  when(state === elementwise) {
    when(phase === (if (bf16) VectorFloat.fma32PipelineStages else 0).U) { state := complete }
      .otherwise { phase := phase + 1.U }
  }
  when(state === complete) { state := idle }

  // Capture the conversion continuously.  This deliberately adds one clock
  // between the accumulator and the store bus, while the accumulator itself
  // remains available for a subsequent MMACC.  A completed matrix has at
  // least the commit/dispatch gap before MSTORE can request its first beat.
  if (bf16) {
    for (e <- 0 until 64) {
      narrowAcc(e) := VectorFloat.narrow(acc(e))
    }
  }

  when(io.publish.valid && !io.flush) {
    valid(io.publish.bits) := true.B
    for (e <- 0 until 64) {
      when(io.publish.bits === 0.U) { a(e) := staging(e)(width - 1, 0) }
      when(io.publish.bits === 1.U) { b(e) := staging(e)(width - 1, 0) }
      when(io.publish.bits === 2.U) {
        acc(e) := staging(e)
        if (bf16) { narrowAcc(e) := VectorFloat.narrow(staging(e)) }
      }
    }
  }
  when(io.flush) { state := idle }
}

/** One head-authorized task, retained until its matching ROB commit. */
class MatrixUnit(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new MatrixTask(p)))
    val commit = Input(Valid(new RobTag(p)))
    val config = Flipped(Decoupled(new MatrixConfig))
    val memReq = Decoupled(new MatrixBeatReq)
    val memResp = Flipped(Decoupled(new MatrixBeatResp))
    val done = Output(Bool())
    val stateFault = Output(Bool())
    val accessFault = Output(Bool())
    val busy = Output(Bool())
    val unitBusy = Output(Vec(3, Bool()))
    val flush = Input(Bool())
  })
  val idle :: transfer :: waitMem :: launch :: compute :: completed :: awaitCommit :: Nil = Enum(7)
  val state = RegInit(idle)
  val task = Reg(new MatrixTask(p))
  val beat = Reg(UInt(3.W))
  val totalBeats = Reg(UInt(3.W))
  val stateFault = RegInit(false.B)
  val accessFault = RegInit(false.B)
  val arrays = Seq(Module(new MatrixArray(true)), Module(new MatrixArray(false)), Module(new MatrixArray(false)))
  val modes = RegInit(VecInit(Seq.fill(3)(false.B)))
  io.config.ready := state === idle && !io.flush
  when(io.config.fire) { modes(io.config.bits.unit) := io.config.bits.mode }
  val validity = VecInit(arrays.map(_.io.validBuffers))
  val nextOp = io.task.bits.op
  val inputs = validity(io.task.bits.unit)
  val needsA = nextOp === MatOp.mmul || nextOp === MatOp.mmacc || nextOp === MatOp.mew || nextOp === MatOp.mtrans
  val needsB = nextOp === MatOp.mmul || nextOp === MatOp.mmacc || nextOp === MatOp.mew
  val needsAcc = nextOp === MatOp.mmacc || nextOp === MatOp.mstore
  val invalid = (needsA && !inputs(0)) || (needsB && !inputs(1)) || (needsAcc && !inputs(2))
  val publish = state === awaitCommit && io.commit.valid && TagMatch(io.commit.bits, task.tag) && !stateFault && !accessFault
  io.task.ready := state === idle && !io.flush
  // Completed tasks still retain ownership, but have no outstanding execution.
  io.busy := state =/= idle && state =/= awaitCommit
  io.done := state === completed
  io.stateFault := stateFault
  io.accessFault := accessFault
  io.unitBusy := VecInit((0 until 3).map(i => state =/= idle && task.unit === i.U))
  io.memReq.valid := state === transfer && !io.flush
  io.memReq.bits.addr := task.addr + (beat << 6)
  io.memReq.bits.write := task.op === MatOp.mstore
  io.memReq.bits.data := VecInit(arrays.map(_.io.storeData))(task.unit)
  io.memReq.bits.mask := "hffffffffffffffff".U
  io.memResp.ready := state === waitMem
  for ((arr, i) <- arrays.zipWithIndex) {
    arr.io.flush := io.flush
    arr.io.mode := modes(i)
    arr.io.storeBeat := beat
    arr.io.start.valid := state === launch && task.unit === i.U
    arr.io.start.bits.op := task.op
    arr.io.loadBeat.valid := io.memResp.fire && !io.memResp.bits.error && task.op === MatOp.mload && task.unit === i.U
    arr.io.loadBeat.bits.buffer := task.buffer
    arr.io.loadBeat.bits.beat := beat
    arr.io.loadBeat.bits.data := io.memResp.bits.data
    arr.io.publish.valid := publish && task.unit === i.U && task.op =/= MatOp.mstore && task.op =/= MatOp.mwait
    arr.io.publish.bits := Mux(task.op === MatOp.mload, task.buffer, 2.U)
  }
  when(io.task.fire) {
    task := io.task.bits
    beat := 0.U
    totalBeats := Mux(io.task.bits.unit === 0.U, 2.U,
      Mux(nextOp === MatOp.mload && io.task.bits.buffer =/= 2.U, 1.U, 4.U))
    stateFault := invalid
    accessFault := false.B
    state := Mux(invalid || nextOp === MatOp.mwait, completed,
      Mux(nextOp === MatOp.mload || nextOp === MatOp.mstore, transfer, launch))
  }
  when(state === launch && VecInit(arrays.map(_.io.start.ready))(task.unit)) { state := compute }
  when(state === compute && !VecInit(arrays.map(_.io.busy))(task.unit)) { state := completed }
  when(io.memReq.fire) { state := waitMem }
  when(io.memResp.fire) {
    when(io.memResp.bits.error) {
      accessFault := true.B
      state := completed
    }.elsewhen(beat === totalBeats - 1.U) { state := completed }
      .otherwise { beat := beat + 1.U; state := transfer }
  }
  when(state === completed) { state := awaitCommit }
  when(state === awaitCommit && io.commit.valid && TagMatch(io.commit.bits, task.tag)) { state := idle }
  when(io.flush) {
    assert(state =/= waitMem, "flush cannot cancel an accepted matrix SRAM request")
    state := idle
  }
}
