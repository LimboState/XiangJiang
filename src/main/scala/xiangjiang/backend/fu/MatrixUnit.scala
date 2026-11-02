package xiangjiang.backend.fu

import chisel3._
import chisel3.util._
import xiangjiang._

/** One output-stationary processing element.  A moves east, B moves
  * south, and this PE retains one FP32/INT32 output.  During a matrix product
  * it accepts at most one pair and performs exactly one multiply-accumulate
  * advance. BF16 advances must be spaced by at least five clocks: a pipelined
  * RNE BF16 product is widened into a pipelined, sequential FP32 accumulator.
  * INT8 MACs remain single-cycle.
  * Valid bits delimit the wavefront and make back-to-back products independent.
  */
class SystolicPE(elementBits: Int, bf16: Boolean) extends Module {
  val io = IO(new Bundle {
    val aIn = Input(UInt(elementBits.W))
    val bIn = Input(UInt(elementBits.W))
    val aInValid = Input(Bool())
    val bInValid = Input(Bool())
    val advance = Input(Bool())
    val clearPipe = Input(Bool())
    val clearAcc = Input(Bool())
    val loadAccValid = Input(Bool())
    val loadAccData = Input(UInt(32.W))
    val aOut = Output(UInt(elementBits.W))
    val bOut = Output(UInt(elementBits.W))
    val aOutValid = Output(Bool())
    val bOutValid = Output(Bool())
    val acc = Output(UInt(32.W))
  })

  val a = Reg(UInt(elementBits.W))
  val b = Reg(UInt(elementBits.W))
  val aValid = RegInit(false.B)
  val bValid = RegInit(false.B)
  val accumulator = Reg(UInt(32.W))

  io.aOut := a
  io.bOut := b
  io.aOutValid := aValid
  io.bOutValid := bValid
  io.acc := accumulator

  when(io.clearPipe) {
    aValid := false.B
    bValid := false.B
  }.elsewhen(io.advance) {
    a := io.aIn
    b := io.bIn
    aValid := io.aInValid
    bValid := io.bInValid
  }

  if (bf16) {
    val cancel = io.clearPipe || io.clearAcc || io.loadAccValid
    val accept = io.advance && io.aInValid && io.bInValid && !cancel
    // Five-cycle rounded product followed by five-cycle FP32 accumulation.
    // The array advances every five clocks, respecting the feedback latency
    // without changing sequential summation or BF16 intermediate rounding.
    val valid = RegInit(VecInit(Seq.fill(9)(false.B)))
    valid(0) := accept
    for (i <- 1 until 9) { valid(i) := valid(i - 1) }
    when(cancel) { valid.foreach(_ := false.B) }
    val product = RegNext(VectorFloat.mul(io.aIn, io.bIn, pipelined = true))
    val sum = VectorFloat.add32(accumulator, VectorFloat.widen(product), pipelined = true)
    when(io.loadAccValid) {
      accumulator := io.loadAccData
    }.elsewhen(io.clearAcc) {
      accumulator := 0.U
    }.elsewhen(valid(8) && !cancel && !reset.asBool) {
      accumulator := sum
    }
  } else {
    when(io.loadAccValid) {
      accumulator := io.loadAccData
    }.elsewhen(io.clearAcc) {
      accumulator := 0.U
    }.elsewhen(io.advance && io.aInValid && io.bInValid) {
      val product = (io.aIn.asSInt * io.bIn.asSInt).pad(32)
      accumulator := (accumulator.asSInt + product).asUInt
    }
  }
}

class MatrixArrayIO extends Bundle {
  val loadBeat = Input(Valid(new Bundle {
    val buffer = UInt(2.W)
    val beat = UInt(2.W)
    val data = UInt(512.W)
  }))
  val loadAcc = Input(Valid(Vec(4, UInt(512.W))))
  val loadAB = Input(Valid(new Bundle {
    val a = UInt(1024.W)
    val b = UInt(1024.W)
  }))
  val storeBeat = Input(UInt(2.W))
  val storeData = Output(UInt(512.W))
  val aBufData = Output(UInt(1024.W))
  val bBufData = Output(UInt(1024.W))
  val accData = Output(Vec(4, UInt(512.W)))
  val start = Flipped(Decoupled(new Bundle { val op = MatOp() }))
  val busy = Output(Bool())
  val flush = Input(Bool())
}

/** A physical 8x8 output-stationary systolic array (XJ-NPU V1 baseline).
  *
  * Row i receives A(i,k) at cycle i+k and column j receives B(k,j) at cycle
  * j+k.  Registered east/south links align equal k values at PE(i,j) in cycle
  * i+j+k. INT8 takes 3*8-2 = 22 compute clocks. BF16 spaces wavefront
  * advances by five clocks to preserve the accumulator recurrence; phase 21
  * accepts the last pair, and phase 23 completes after draining (116 clocks).
  * All 64 PEs operate concurrently.
  *
  * MMUL zeroes the working accumulator; MMACC seeds the accumulator from the
  * committed MASF ACC before the wavefront starts.
  */
class MatrixArray(bf16: Boolean) extends Module {
  val io = IO(new MatrixArrayIO)
  private val elementBits = if (bf16) 16 else 8
  private val idle :: prepare :: computing :: elementwise :: complete :: Nil = Enum(5)
  private val state = RegInit(idle)
  private val op = Reg(MatOp())
  private val phase = RegInit(0.U(6.W))
  private val subcycle = RegInit(0.U(3.W))
  private val advance = if (bf16) subcycle === 0.U else true.B
  private val aBuf = Reg(Vec(64, UInt(elementBits.W)))
  private val bBuf = Reg(Vec(64, UInt(elementBits.W)))
  private val pes = Seq.tabulate(8, 8) { (_, _) =>
    Module(new SystolicPE(elementBits, bf16))
  }
  private val peAcc = VecInit(pes.flatten.map(_.io.acc))

  io.start.ready := state === idle && !io.flush
  io.busy := state =/= idle
  io.aBufData := aBuf.asUInt.pad(1024)
  io.bBufData := bBuf.asUInt.pad(1024)
  io.accData := VecInit((0 until 4).map { b =>
    VecInit((0 until 16).map(l => peAcc(Cat(b.U(2.W), l.U(4.W))))).asUInt
  })

  for (pe <- pes.flatten) {
    pe.io.advance := state === computing && advance
    pe.io.clearPipe := io.flush || state === prepare
    pe.io.clearAcc := state === prepare && op === MatOp.mmul
    pe.io.loadAccValid := false.B
    pe.io.loadAccData := 0.U
  }

  // Prepare seeds A/B and (for MMACC) the accumulators from committed MASF.
  when(state === prepare) {
    val aBits = if (bf16) io.loadAB.bits.a else io.loadAB.bits.a(511, 0)
    val bBits = if (bf16) io.loadAB.bits.b else io.loadAB.bits.b(511, 0)
    aBuf := aBits.asTypeOf(Vec(64, UInt(elementBits.W)))
    bBuf := bBits.asTypeOf(Vec(64, UInt(elementBits.W)))
    when(op === MatOp.mmacc) {
      for (e <- 0 until 64) {
        val beat = e >> 4
        val local = e % 16
        pes(e / 8)(e % 8).io.loadAccValid := true.B
        pes(e / 8)(e % 8).io.loadAccData := io.loadAcc.bits(beat)(32 * local + 31, 32 * local)
      }
    }
  }

  // Complete-tile MLOADs populate the edge injection buffers.  ACC loads are
  // distributed directly to the output-stationary accumulator in each PE.
  // MU0 tile formats: A/B 8x8 BF16 = 2 x 64-byte beats, ACC 8x8 BF16 = 2 beats.
  // MU1/MU2 formats: A/B 8x8 INT8 = 1 beat, ACC 8x8 INT32 = 4 beats.
  when(io.loadBeat.valid) {
    val beat = io.loadBeat.bits.beat
    val data = io.loadBeat.bits.data
    if (bf16) {
      for (e <- 0 until 64) {
        val local = e % 32
        when((e >> 5).U === beat) {
          when(io.loadBeat.bits.buffer === 0.U) {
            aBuf(e) := data(16 * local + 15, 16 * local)
          }.elsewhen(io.loadBeat.bits.buffer === 1.U) {
            bBuf(e) := data(16 * local + 15, 16 * local)
          }.otherwise {
            pes(e / 8)(e % 8).io.loadAccValid := true.B
            pes(e / 8)(e % 8).io.loadAccData :=
              VectorFloat.widen(data(16 * local + 15, 16 * local))
          }
        }
      }
    } else {
      for (e <- 0 until 64) {
        when(io.loadBeat.bits.buffer === 2.U) {
          val accLocal = e % 16
          when((e >> 4).U === beat) {
            pes(e / 8)(e % 8).io.loadAccValid := true.B
            pes(e / 8)(e % 8).io.loadAccData := data(32 * accLocal + 31, 32 * accLocal)
          }
        }.elsewhen(beat === 0.U) {
          when(io.loadBeat.bits.buffer === 0.U) {
            aBuf(e) := data(8 * e + 7, 8 * e)
          }.elsewhen(io.loadBeat.bits.buffer === 1.U) {
            bBuf(e) := data(8 * e + 7, 8 * e)
          }
        }
      }
    }
  }

  // Skewed edge injection and registered nearest-neighbour links.
  for (i <- 0 until 8; j <- 0 until 8) {
    val pe = pes(i)(j)
    if (j == 0) {
      val k = (phase - i.U)(2, 0)
      pe.io.aIn := aBuf(Cat(i.U(3.W), k))
      pe.io.aInValid := state === computing && phase >= i.U && phase < (i + 8).U
    } else {
      pe.io.aIn := pes(i)(j - 1).io.aOut
      pe.io.aInValid := pes(i)(j - 1).io.aOutValid
    }
    if (i == 0) {
      val k = (phase - j.U)(2, 0)
      pe.io.bIn := bBuf(Cat(k, j.U(3.W)))
      pe.io.bInValid := state === computing && phase >= j.U && phase < (j + 8).U
    } else {
      pe.io.bIn := pes(i - 1)(j).io.bOut
      pe.io.bInValid := pes(i - 1)(j).io.bOutValid
    }
  }

  // Elementwise and transpose operations do not need a matrix wavefront.  A
  // shared lane writes one PE accumulator per cycle while MMUL/MMACC always use
  // the physical systolic datapath above.
  // MTRANS: ACC[i][j] = widen/sign-extend(A[j][i]).
  // MEW: BF16 product is RNE-rounded to BF16 and then exactly widened to FP32;
  // INT8 product is sign-extended to INT32.
  val elementResult = WireDefault(0.U(32.W))
  if (bf16) {
    elementResult := VectorFloat.widen(aBuf(Cat(phase(2, 0), phase(5, 3))))
    val product = VectorFloat.mul(aBuf(phase), bBuf(phase), pipelined = true)
    when(op === MatOp.mew) {
      elementResult := VectorFloat.widen(product)
    }
  } else {
    elementResult := aBuf(Cat(phase(2, 0), phase(5, 3))).asSInt.pad(32).asUInt
    when(op === MatOp.mew) {
      elementResult := (aBuf(phase).asSInt * bBuf(phase).asSInt).pad(32).asUInt
    }
  }
  for ((pe, e) <- pes.flatten.zipWithIndex) {
    when(state === elementwise && phase === e.U && (if (bf16) subcycle === 4.U else true.B)) {
      pe.io.loadAccValid := true.B
      pe.io.loadAccData := elementResult
    }
  }

  if (bf16) {
    // 2 beats: beat b covers rows 4b..4b+3 (32 x BF16 elements).
    io.storeData := VecInit((0 until 32).map { local =>
      val e = Cat(io.storeBeat(0), local.U(5.W))
      VectorFloat.narrow(peAcc(e))
    }).asUInt
  } else {
    // 4 beats: beat b covers rows 2b..2b+1 (16 x INT32 elements).
    io.storeData := VecInit((0 until 16).map { local =>
      peAcc(Cat(io.storeBeat(1, 0), local.U(4.W)))
    }).asUInt
  }

  when(io.flush) {
    state := idle
    phase := 0.U
    subcycle := 0.U
  }.otherwise {
    when(state === computing || state === elementwise) {
      subcycle := Mux(subcycle === 4.U, 0.U, subcycle + 1.U)
    }
    when(io.start.fire) {
      op := io.start.bits.op
      phase := 0.U
      subcycle := 0.U
      state := prepare
    }
    when(state === prepare) {
      phase := 0.U
      state := Mux(op === MatOp.mmul || op === MatOp.mmacc, computing, elementwise)
    }
    when(state === computing && advance) {
      // Last pair is accepted at phase 21; two five-clock drain slots cover
      // product and accumulator pipelines. INT8 retains its original cadence.
      when(phase === (if (bf16) 23 else 21).U) {
        state := complete
      }.otherwise {
        phase := phase + 1.U
      }
    }
    when(state === elementwise && (if (bf16) subcycle === 4.U else true.B)) {
      when(phase === 63.U) {
        state := complete
      }.otherwise {
        phase := phase + 1.U
      }
    }
    when(state === complete) { state := idle }
  }
}

/** Staging record of one completed but not yet committed matrix instruction.
  * Holds the full tile payload plus the owner ROBTag; the ROB head publishes
  * it into the committed MASF on successful commit. Flush discards it.
  */
class MatrixStaging(p: XJParams) extends Bundle {
  val tag = new RobTag(p)
  val op = MatOp()
  val unit = UInt(2.W)
  val buffer = UInt(2.W)
  val beats = UInt(2.W) // encoded 1..4 as 0..3
  val data = Vec(4, UInt(512.W))
}

/** Matrix task sequencer with committed-state / staging separation.
  *
  * Three layers of state (docs/XJ-NPU.md 3.3.5):
  *   - Committed State / MASF: per-unit A, B, ACC tiles and INT8 modes,
  *     updated only by ROB-head commit publication.
  *   - Staging: completed task payloads tagged with their owner ROBTag.
  *   - Physical transient: array buffers, PE accumulators, wavefront counters.
  *
  * A scoreboard tracks the uncommitted writer of each unit's A, B and ACC
  * resource so that readers only observe committed state. Tasks execute one
  * at a time in program order; stores additionally require the ROB head.
  */
class MatrixUnit(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new MatrixTask(p)))
    val publish = Input(Valid(new MatrixPublish(p)))
    val memReq = Decoupled(new MatrixBeatReq)
    val memResp = Flipped(Decoupled(new MatrixBeatResp))
    val done = Output(Bool())
    val busy = Output(Bool())
    val unitBusy = Output(Vec(3, Bool()))
    val flush = Input(Bool())
  })

  private val idle :: prepareMem :: loadStore :: waitMem :: compute :: waitArray :: Nil = Enum(6)
  private val state = RegInit(idle)
  private val task = Reg(new MatrixTask(p))
  private val beat = Reg(UInt(2.W))
  private val totalBeats = Reg(UInt(2.W)) // encoded 1..4 as 0..3
  private val base = Reg(UInt(32.W))
  private val pending = RegInit(false.B)
  private val storeBuf = Reg(Vec(4, UInt(512.W)))
  // Isolate tile/beat selection and BF16 rounding from the DTCM arbitration
  // and BRAM input path. Hold the entire request stable under backpressure.
  private val memoryRequest = Reg(new MatrixBeatReq)

  val bf16Array = Module(new MatrixArray(bf16 = true))
  val int8Array0 = Module(new MatrixArray(bf16 = false))
  val int8Array1 = Module(new MatrixArray(bf16 = false))
  val arrays = Seq(bf16Array, int8Array0, int8Array1)

  // Committed Matrix Architectural State (MASF).
  val masfA = Reg(Vec(3, Vec(64, UInt(16.W))))
  val masfB = Reg(Vec(3, Vec(64, UInt(16.W))))
  val masfAcc = Reg(Vec(3, Vec(64, UInt(32.W))))
  val modes = RegInit(VecInit(Seq.fill(3)(false.B))) // OS=false, WS=true; MU0 fixed OS

  // Scoreboard: at most one uncommitted writer per (unit, resource).
  // Resources: 0=A, 1=B, 2=ACC. MODE ordering is enforced by the MSETMODE
  // dispatch barrier.
  val writerValid = RegInit(VecInit(Seq.fill(9)(false.B)))
  val writerTag = Reg(Vec(9, new RobTag(p)))

  // Staging FIFO (pipe-through, flushable): matrix tasks complete and enqueue
  // in program order; the ROB head publishes (dequeues) in the same order.
  val staging = Reg(Vec(p.nRob, new MatrixStaging(p)))
  val qHead = RegInit(0.U(p.robBits.W))
  val qTail = RegInit(0.U(p.robBits.W))
  val qCount = RegInit(0.U(log2Ceil(p.nRob + 1).W))
  val qFull = qCount === p.nRob.U
  val qEmpty = qCount === 0.U

  val nextOp = io.task.bits.op
  val nextUnit = io.task.bits.unit
  val nextBuffer = io.task.bits.buffer
  val readsA = nextOp === MatOp.mmul || nextOp === MatOp.mmacc ||
    nextOp === MatOp.mew || nextOp === MatOp.mtrans
  val readsB = nextOp === MatOp.mmul || nextOp === MatOp.mmacc || nextOp === MatOp.mew
  val readsAcc = nextOp === MatOp.mmacc || nextOp === MatOp.mstore
  val writesA = nextOp === MatOp.mload && nextBuffer === 0.U
  val writesB = nextOp === MatOp.mload && nextBuffer === 1.U
  val writesAcc = (nextOp === MatOp.mload && nextBuffer === 2.U) ||
    nextOp === MatOp.mmul || nextOp === MatOp.mmacc ||
    nextOp === MatOp.mew || nextOp === MatOp.mtrans
  val resourcesClear = (!readsA && !writesA || !writerValid(nextUnit * 3.U + 0.U)) &&
    (!readsB && !writesB || !writerValid(nextUnit * 3.U + 1.U)) &&
    (!readsAcc && !writesAcc || !writerValid(nextUnit * 3.U + 2.U))

  io.task.ready := state === idle && !io.flush && resourcesClear
  io.busy := state =/= idle
  io.done := pending
  io.unitBusy := VecInit(arrays.map(_.io.busy))

  // MU0: 2 beats (128 B tiles); INT8 A/B load: 1 beat (64 B); INT8 ACC
  // load/store: 4 beats (256 B).
  val nextTotalBeats = Mux(nextUnit === 0.U, 1.U,
    Mux(nextOp === MatOp.mload && nextBuffer =/= 2.U, 0.U, 3.U))

  val selected = task.unit
  val aTile = MuxLookup(selected, 0.U(1024.W))(Seq(
    0.U -> bf16Array.io.aBufData,
    1.U -> int8Array0.io.aBufData,
    2.U -> int8Array1.io.aBufData))
  val bTile = MuxLookup(selected, 0.U(1024.W))(Seq(
    0.U -> bf16Array.io.bBufData,
    1.U -> int8Array0.io.bBufData,
    2.U -> int8Array1.io.bBufData))
  val accBeats = MuxLookup(selected, VecInit(Seq.fill(4)(0.U(512.W))))(Seq(
    0.U -> bf16Array.io.accData,
    1.U -> int8Array0.io.accData,
    2.U -> int8Array1.io.accData))
  val startReady = MuxLookup(selected, false.B)(Seq(
    0.U -> bf16Array.io.start.ready,
    1.U -> int8Array0.io.start.ready,
    2.U -> int8Array1.io.start.ready))
  val arrayDone = MuxLookup(selected, false.B)(Seq(
    0.U -> !bf16Array.io.busy,
    1.U -> !int8Array0.io.busy,
    2.U -> !int8Array1.io.busy))

  // MSTORE reads committed ACC; capture it when the task fires.
  when(io.task.fire && nextOp === MatOp.mstore) {
    storeBuf := masfAcc(nextUnit).asUInt.asTypeOf(Vec(4, UInt(512.W)))
  }
  val storeDataBf16 = VecInit((0 until 32).map { local =>
    val half = local / 16
    VectorFloat.narrow(storeBuf(Cat(beat, half.U)(1, 0))(32 * (local % 16) + 31, 32 * (local % 16)))
  }).asUInt

  io.memResp.ready := state === waitMem && !io.flush
  io.memReq.valid := state === loadStore && !io.flush
  io.memReq.bits := memoryRequest
  when(state === prepareMem && !io.flush) {
    memoryRequest.addr := base + (beat << 6)
    memoryRequest.write := task.op === MatOp.mstore
    memoryRequest.data := Mux(task.op === MatOp.mstore,
      Mux(selected === 0.U, storeDataBf16, storeBuf(beat)), 0.U)
    memoryRequest.mask := "hffffffffffffffff".U(64.W)
  }

  for ((arr, idx) <- arrays.zipWithIndex) {
    arr.io.flush := io.flush
    arr.io.start.bits.op := task.op
    arr.io.start.valid := state === compute && selected === idx.U
    arr.io.loadAcc.valid := state === compute && task.op === MatOp.mmacc && selected === idx.U
    arr.io.loadAcc.bits := masfAcc(idx).asUInt.asTypeOf(Vec(4, UInt(512.W)))
    arr.io.loadAB.valid := state === compute && selected === idx.U
    arr.io.loadAB.bits.a := Mux(idx.U === 0.U, masfA(idx).asUInt,
      VecInit((0 until 64).map(e => masfA(idx)(e)(7, 0))).asUInt.pad(1024))
    arr.io.loadAB.bits.b := Mux(idx.U === 0.U, masfB(idx).asUInt,
      VecInit((0 until 64).map(e => masfB(idx)(e)(7, 0))).asUInt.pad(1024))
    arr.io.loadBeat.valid := state === waitMem && task.op === MatOp.mload &&
      io.memResp.valid && selected === idx.U
    arr.io.loadBeat.bits.buffer := task.buffer
    arr.io.loadBeat.bits.beat := beat
    arr.io.loadBeat.bits.data := io.memResp.bits.data
    arr.io.storeBeat := beat
  }

  // Completion readback: tile loads replay the array buffers; ACC writes and
  // compute tasks replay the PE accumulators.  The current beat's data has
  // not landed in the registers yet, so the staging record takes it directly
  // from the in-flight response.
  val stagingData = WireDefault(0.U.asTypeOf(Vec(4, UInt(512.W))))
  val selectedTile = Mux(task.buffer === 0.U, aTile, bTile)
  val inFlight = state === waitMem && io.memResp.valid
  val incomingBf16 = io.memResp.bits.data
  when(task.op === MatOp.mload && task.buffer =/= 2.U) {
    stagingData(0) := Mux(inFlight && beat === 0.U, incomingBf16, selectedTile(511, 0))
    stagingData(1) := Mux(inFlight && beat === 1.U && selected === 0.U, incomingBf16,
      selectedTile(1023, 512))
    stagingData(2) := 0.U
    stagingData(3) := 0.U
  }.elsewhen(task.op === MatOp.mload || task.op === MatOp.mmul ||
    task.op === MatOp.mmacc || task.op === MatOp.mew || task.op === MatOp.mtrans) {
    stagingData := accBeats
    when(inFlight && task.op === MatOp.mload) {
      // Staging is always INT32-packed: MU0 widens the incoming BF16 lanes.
      when(selected === 0.U) {
        stagingData(Cat(beat, 0.U)(1, 0)) := VecInit((0 until 16).map { l =>
          VectorFloat.widen(incomingBf16(16 * l + 15, 16 * l))
        }).asUInt
        stagingData(Cat(beat, 1.U)(1, 0)) := VecInit((0 until 16).map { l =>
          VectorFloat.widen(incomingBf16(16 * (l + 16) + 15, 16 * (l + 16)))
        }).asUInt
      }.otherwise {
        stagingData(beat) := incomingBf16
      }
    }
  }

  val completingLoad = state === waitMem && io.memResp.fire && beat === totalBeats
  val completingCompute = state === waitArray && arrayDone
  val publishesTask = task.op === MatOp.mload || task.op === MatOp.mmul ||
    task.op === MatOp.mmacc || task.op === MatOp.mew || task.op === MatOp.mtrans
  val doEnq = (completingLoad || completingCompute) && publishesTask && !qFull && !io.flush
  val enqRecord = WireDefault(0.U.asTypeOf(new MatrixStaging(p)))
  enqRecord.tag := task.tag
  enqRecord.op := task.op
  enqRecord.unit := task.unit
  enqRecord.buffer := task.buffer
  enqRecord.beats := totalBeats
  enqRecord.data := stagingData

  // ROB-head publication into the committed MASF.
  val publishState = io.publish.valid && io.publish.bits.op =/= MatOp.msetmode
  val doDeq = publishState
  val headRecord = Mux(qEmpty, enqRecord, staging(qHead))
  when(doEnq) {
    staging(qTail) := enqRecord
  }

  when(io.publish.valid) {
    when(io.publish.bits.op === MatOp.msetmode) {
      // MU0 is fixed output-stationary; decode already rejects WS for MU0.
      modes(io.publish.bits.unit) := io.publish.bits.mode
    }.otherwise {
      assert(!qEmpty, "matrix commit requires a completed staging record")
      assert(TagMatch(headRecord.tag, io.publish.bits.tag),
        "matrix staging publication must follow program order")
      when(headRecord.op === MatOp.mload) {
        switch(headRecord.buffer) {
          is(0.U) {
            for (e <- 0 until 64) {
              masfA(headRecord.unit)(e) := Mux(headRecord.unit === 0.U,
                headRecord.data(e >> 5)(16 * (e % 32) + 15, 16 * (e % 32)),
                headRecord.data(0)(8 * e + 7, 8 * e))
            }
          }
          is(1.U) {
            for (e <- 0 until 64) {
              masfB(headRecord.unit)(e) := Mux(headRecord.unit === 0.U,
                headRecord.data(e >> 5)(16 * (e % 32) + 15, 16 * (e % 32)),
                headRecord.data(0)(8 * e + 7, 8 * e))
            }
          }
          is(2.U) {
            for (e <- 0 until 64) {
              masfAcc(headRecord.unit)(e) :=
                headRecord.data(e >> 4)(32 * (e % 16) + 31, 32 * (e % 16))
            }
          }
        }
      }.otherwise {
        for (e <- 0 until 64) {
          masfAcc(headRecord.unit)(e) :=
            headRecord.data(e >> 4)(32 * (e % 16) + 31, 32 * (e % 16))
        }
      }
      when(headRecord.op === MatOp.mload) {
        when(headRecord.buffer === 0.U) {
          writerValid(headRecord.unit * 3.U + 0.U) := false.B
        }.elsewhen(headRecord.buffer === 1.U) {
          writerValid(headRecord.unit * 3.U + 1.U) := false.B
        }.otherwise {
          writerValid(headRecord.unit * 3.U + 2.U) := false.B
        }
      }.otherwise {
        writerValid(headRecord.unit * 3.U + 2.U) := false.B
      }
    }
  }

  when(io.task.fire) {
    task := io.task.bits
    beat := 0.U
    totalBeats := nextTotalBeats
    base := io.task.bits.addr
    when(nextOp === MatOp.mbarrier || nextOp === MatOp.mfence) {
      pending := true.B
    }.otherwise {
      state := Mux(nextOp === MatOp.mload || nextOp === MatOp.mstore, prepareMem, compute)
    }
    when(writesA) {
      writerValid(nextUnit * 3.U + 0.U) := true.B
      writerTag(nextUnit * 3.U + 0.U) := io.task.bits.tag
    }
    when(writesB) {
      writerValid(nextUnit * 3.U + 1.U) := true.B
      writerTag(nextUnit * 3.U + 1.U) := io.task.bits.tag
    }
    when(writesAcc) {
      writerValid(nextUnit * 3.U + 2.U) := true.B
      writerTag(nextUnit * 3.U + 2.U) := io.task.bits.tag
    }
  }

  when(io.flush) {
    state := idle
    pending := false.B
    writerValid.foreach(_ := false.B)
    qHead := 0.U
    qTail := 0.U
    qCount := 0.U
  }.otherwise {
    when(pending) { pending := false.B }
    when(state === compute) {
      when(startReady) { state := waitArray }
    }
    when(state === prepareMem) { state := loadStore }
    when(state === loadStore) {
      when(io.memReq.fire) { state := waitMem }
    }
    when(state === waitMem) {
      when(io.memResp.fire) {
        when(beat === totalBeats) {
          pending := true.B
          state := idle
        }.otherwise {
          beat := beat + 1.U
          state := prepareMem
        }
      }
    }
    when(state === waitArray) {
      when(arrayDone) {
        pending := true.B
        state := idle
      }
    }
  }

  // FIFO pointer/count maintenance.
  val netEnq = doEnq && !(qEmpty && doDeq)
  val netDeq = doDeq && !(qEmpty && doEnq)
  when(netEnq) { qTail := Mux(qTail === (p.nRob - 1).U, 0.U, qTail + 1.U) }
  when(netDeq) { qHead := Mux(qHead === (p.nRob - 1).U, 0.U, qHead + 1.U) }
  when(netEnq =/= netDeq) {
    qCount := Mux(netEnq, qCount + 1.U, qCount - 1.U)
  }
}
