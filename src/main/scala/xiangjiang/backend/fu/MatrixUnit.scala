package xiangjiang.backend.fu

import chisel3._
import chisel3.util._
import xiangjiang._

/** Physical 8x8 PE array with committed and working state. OS keeps each
  * output stationary over k; INT8 WS keeps B[k,j] at PE[k,j] and routes one
  * output row's partial sums down the k dimension. Both preserve k order.
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
  val width = if (bf16) 16 else 8
  val a = RegInit(VecInit(Seq.fill(64)(0.U(width.W))))
  val b = RegInit(VecInit(Seq.fill(64)(0.U(width.W))))
  val acc = RegInit(VecInit(Seq.fill(64)(0.U(32.W))))
  val valid = RegInit(VecInit(Seq.fill(3)(false.B)))
  val staging = Reg(Vec(64, UInt(32.W)))
  val busy = RegInit(false.B)
  val op = Reg(MatOp())
  val phase = Reg(UInt(3.W))
  val mode = Reg(Bool())
  io.validBuffers := valid
  io.busy := busy
  io.start.ready := !busy && !io.flush

  when(io.loadBeat.valid && !io.flush) {
    for (e <- 0 until 64) {
      val inputPerBeat = 512 / width
      val inputValue = io.loadBeat.bits.data((e % inputPerBeat + 1) * width - 1,
        (e % inputPerBeat) * width)
      if (bf16) {
        when(io.loadBeat.bits.beat === (e / 32).U) {
          staging(e) := Mux(io.loadBeat.bits.buffer === 2.U, VectorFloat.widen(inputValue), inputValue)
        }
      } else {
        when(io.loadBeat.bits.buffer =/= 2.U && io.loadBeat.bits.beat === 0.U) {
          staging(e) := inputValue
        }
        when(io.loadBeat.bits.buffer === 2.U && io.loadBeat.bits.beat === (e / 16).U) {
          staging(e) := io.loadBeat.bits.data((e % 16 + 1) * 32 - 1, (e % 16) * 32)
        }
      }
    }
  }
  if (bf16) {
    io.storeData := VecInit((0 until 32).map(i =>
      VectorFloat.narrow(acc(Cat(io.storeBeat(0), i.U(5.W)))))).asUInt
  } else {
    io.storeData := VecInit((0 until 16).map(i =>
      acc(Cat(io.storeBeat(1, 0), i.U(4.W))))).asUInt
  }

  when(io.start.fire) {
    op := io.start.bits.op
    phase := 0.U
    mode := io.mode
    for (e <- 0 until 64) { staging(e) := Mux(io.start.bits.op === MatOp.mmacc, acc(e), 0.U) }
    busy := true.B
  }
  val pes = Seq.fill(64)(Module(new MatrixPE(bf16)))
  val multiply = op === MatOp.mmul || op === MatOp.mmacc
  val ws = if (bf16) false.B else mode && multiply
  for (r <- 0 until 8; c <- 0 until 8) {
    val e = r * 8 + c
    val pe = pes(e)
    val osA = Mux(multiply, a(Cat(r.U(3.W), phase)), a(e))
    val osB = Mux(multiply, b(Cat(phase, c.U(3.W))), b(e))
    pe.io.a := Mux(busy, Mux(ws, a(Cat(phase, r.U(3.W))), osA), 0.U)
    pe.io.b := Mux(busy, Mux(ws, b(e), osB), 0.U)
    val wsSum = if (r == 0) Mux(op === MatOp.mmacc, acc(Cat(phase, c.U(3.W))), 0.U)
      else pes((r - 1) * 8 + c).io.result
    pe.io.sum := Mux(ws, wsSum, staging(e))
    pe.io.multiplyOnly := op === MatOp.mew
    val transposed = if (bf16) VectorFloat.widen(a(c * 8 + r))
      else a(c * 8 + r).asSInt.pad(32).asUInt
    when(busy) {
      when(ws) {
        when(phase === r.U) { staging(e) := pes(56 + c).io.result }
      }.otherwise {
        staging(e) := Mux(op === MatOp.mtrans, transposed, pe.io.result)
      }
    }
  }
  when(busy) {
    when(!multiply || phase === 7.U) { busy := false.B }.otherwise { phase := phase + 1.U }
  }
  when(io.publish.valid && !io.flush) {
    valid(io.publish.bits) := true.B
    for (e <- 0 until 64) {
      when(io.publish.bits === 0.U) { a(e) := staging(e)(width - 1, 0) }
      when(io.publish.bits === 1.U) { b(e) := staging(e)(width - 1, 0) }
      when(io.publish.bits === 2.U) { acc(e) := staging(e) }
    }
  }
  when(io.flush) { busy := false.B }
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
