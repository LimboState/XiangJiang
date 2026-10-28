package xiangjiang.backend.fu

import chisel3._
import chisel3.util._
import xiangjiang._

/** One output-stationary processing element.  A moves east, B moves
  * south, and this PE retains one FP32/INT32 output.  During a matrix product
  * it accepts at most one pair and performs exactly one multiply-accumulate
  * per cycle. BF16 MACs have two stages: an RNE BF16 product register, then
  * exact widening and FP32 accumulation. INT8 MACs remain single-cycle.
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
    val product = Reg(UInt(16.W))
    val productValid = RegInit(false.B)
    val cancel = io.clearPipe || io.clearAcc || io.loadAccValid
    val accept = io.advance && io.aInValid && io.bInValid && !cancel
    // Advance gates input acceptance, not retirement: the last product must
    // drain even after A/B propagation stops. Bubbles must never replay a MAC.
    productValid := accept
    when(accept) { product := VectorFloat.mul(io.aIn, io.bIn) }

    when(io.loadAccValid) {
      accumulator := io.loadAccData
    }.elsewhen(io.clearAcc) {
      accumulator := 0.U
    }.elsewhen(productValid && !io.clearPipe && !reset.asBool) {
      accumulator := VectorFloat.add32(accumulator, VectorFloat.widen(product))
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
    val beat = UInt(5.W)
    val data = UInt(512.W)
  }))
  val storeBeat = Input(UInt(5.W))
  val storeData = Output(UInt(512.W))
  val start = Flipped(Decoupled(new Bundle { val op = MatOp() }))
  val busy = Output(Bool())
  val flush = Input(Bool())
}

/** A physical 16x16 output-stationary systolic array.
  *
  * Row i receives A(i,k) at cycle i+k and column j receives B(k,j) at cycle
  * j+k.  Registered east/south links align equal k values at PE(i,j) in cycle
  * i+j+k.  Consequently a 16x16 product takes 3*16-2 = 46 wavefront cycles;
  * all 256 PEs work concurrently and each PE accepts one MAC per active cycle.
  * BF16 adds one drain cycle for the registered product (47 compute cycles).
  */
class MatrixArray(bf16: Boolean) extends Module {
  val io = IO(new MatrixArrayIO)
  private val elementBits = if (bf16) 16 else 8
  private val idle :: prepare :: computing :: elementwise :: complete :: Nil = Enum(5)
  private val state = RegInit(idle)
  private val op = Reg(MatOp())
  private val phase = RegInit(0.U(8.W))
  private val aBuf = Reg(Vec(256, UInt(elementBits.W)))
  private val bBuf = Reg(Vec(256, UInt(elementBits.W)))
  private val pes = Seq.tabulate(16, 16) { (_, _) =>
    Module(new SystolicPE(elementBits, bf16))
  }
  private val peAcc = VecInit(pes.flatten.map(_.io.acc))

  io.start.ready := state === idle && !io.flush
  io.busy := state =/= idle

  for (pe <- pes.flatten) {
    pe.io.advance := state === computing
    pe.io.clearPipe := io.flush || state === prepare
    pe.io.clearAcc := state === prepare && op === MatOp.mmul
    pe.io.loadAccValid := false.B
    pe.io.loadAccData := 0.U
  }

  // Complete-tile MLOADs populate the edge injection buffers.  ACC loads are
  // distributed directly to the output-stationary accumulator in each PE.
  when(io.loadBeat.valid) {
    val beat = io.loadBeat.bits.beat
    val data = io.loadBeat.bits.data
    if (bf16) {
      for (e <- 0 until 256) {
        val local = e % 32
        when((e >> 5).U === beat) {
          when(io.loadBeat.bits.buffer === 0.U) {
            aBuf(e) := data(16 * local + 15, 16 * local)
          }.elsewhen(io.loadBeat.bits.buffer === 1.U) {
            bBuf(e) := data(16 * local + 15, 16 * local)
          }.otherwise {
            pes(e / 16)(e % 16).io.loadAccValid := true.B
            pes(e / 16)(e % 16).io.loadAccData :=
              VectorFloat.widen(data(16 * local + 15, 16 * local))
          }
        }
      }
    } else {
      for (e <- 0 until 256) {
        val local = e % 64
        when((e >> 6).U === beat) {
          when(io.loadBeat.bits.buffer === 0.U) {
            aBuf(e) := data(8 * local + 7, 8 * local)
          }.elsewhen(io.loadBeat.bits.buffer === 1.U) {
            bBuf(e) := data(8 * local + 7, 8 * local)
          }
        }
        when(io.loadBeat.bits.buffer === 2.U && (e >> 4).U === beat) {
          val accLocal = e % 16
          pes(e / 16)(e % 16).io.loadAccValid := true.B
          pes(e / 16)(e % 16).io.loadAccData :=
            data(32 * accLocal + 31, 32 * accLocal)
        }
      }
    }
  }

  // Skewed edge injection and registered nearest-neighbour links.
  for (i <- 0 until 16; j <- 0 until 16) {
    val pe = pes(i)(j)
    if (j == 0) {
      val k = (phase - i.U)(3, 0)
      pe.io.aIn := aBuf(Cat(i.U(4.W), k))
      pe.io.aInValid := state === computing && phase >= i.U && phase < (i + 16).U
    } else {
      pe.io.aIn := pes(i)(j - 1).io.aOut
      pe.io.aInValid := pes(i)(j - 1).io.aOutValid
    }
    if (i == 0) {
      val k = (phase - j.U)(3, 0)
      pe.io.bIn := bBuf(Cat(k, j.U(4.W)))
      pe.io.bInValid := state === computing && phase >= j.U && phase < (j + 16).U
    } else {
      pe.io.bIn := pes(i - 1)(j).io.bOut
      pe.io.bInValid := pes(i - 1)(j).io.bOutValid
    }
  }

  // Elementwise and transpose operations do not need a matrix wavefront.  A
  // shared lane writes one PE accumulator per cycle while MMUL/MMACC always use
  // the physical systolic datapath above.
  val elementResult = WireDefault(0.U(32.W))
  if (bf16) {
    elementResult := VectorFloat.widen(aBuf(Cat(phase(3, 0), phase(7, 4))))
    when(op === MatOp.mew) {
      elementResult := VectorFloat.mul32(VectorFloat.widen(aBuf(phase)),
        VectorFloat.widen(bBuf(phase)))
    }
  } else {
    elementResult := aBuf(Cat(phase(3, 0), phase(7, 4))).asSInt.pad(32).asUInt
    when(op === MatOp.mew) {
      elementResult := (aBuf(phase).asSInt * bBuf(phase).asSInt).pad(32).asUInt
    }
  }
  for ((pe, e) <- pes.flatten.zipWithIndex) {
    when(state === elementwise && phase === e.U) {
      pe.io.loadAccValid := true.B
      pe.io.loadAccData := elementResult
    }
  }

  if (bf16) {
    io.storeData := VecInit((0 until 32).map { local =>
      val e = Cat(io.storeBeat(2, 0), local.U(5.W))
      VectorFloat.narrow(peAcc(e))
    }).asUInt
  } else {
    io.storeData := VecInit((0 until 16).map { local =>
      peAcc(Cat(io.storeBeat(3, 0), local.U(4.W)))
    }).asUInt
  }

  when(io.flush) {
    state := idle
    phase := 0.U
  }.otherwise {
    when(io.start.fire) {
      op := io.start.bits.op
      phase := 0.U
      state := Mux(io.start.bits.op === MatOp.mmul || io.start.bits.op === MatOp.mmacc,
        prepare, elementwise)
    }
    when(state === prepare) {
      phase := 0.U
      state := computing
    }
    when(state === computing) {
      // Last pair reaches PE(15,15) at phase 45. BF16 retires that
      // registered product at phase 46 before reporting completion.
      when(phase === (if (bf16) 46 else 45).U) {
        state := complete
      }.otherwise {
        phase := phase + 1.U
      }
    }
    when(state === elementwise) {
      when(phase === 255.U) {
        state := complete
      }.otherwise {
        phase := phase + 1.U
      }
    }
    when(state === complete) { state := idle }
  }
}

/** Matrix task sequencer: ordered MLOAD/MSTORE tile transfers and per-unit
  * compute/MWAIT tasks.  The LSU keeps tasks ordered; this unit only starts
  * one task at a time and drives the 64-byte DTCM beat interface.
  */
class MatrixUnit(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new MatrixTask))
    val config = Flipped(Decoupled(new MatrixConfig))
    val memReq = Decoupled(new MatrixBeatReq)
    val memResp = Flipped(Decoupled(new MatrixBeatResp))
    val done = Output(Bool())
    val busy = Output(Bool())
    val unitBusy = Output(Vec(3, Bool()))
    val flush = Input(Bool())
  })

  private val idle :: loadStore :: waitMem :: compute :: waitArray :: Nil = Enum(5)
  private val state = RegInit(idle)
  private val task = Reg(new MatrixTask)
  private val beat = Reg(UInt(5.W))
  private val totalBeats = Reg(UInt(5.W))
  private val base = Reg(UInt(32.W))
  private val pending = RegInit(false.B)

  val bf16Array = Module(new MatrixArray(bf16 = true))
  val int8Array0 = Module(new MatrixArray(bf16 = false))
  val int8Array1 = Module(new MatrixArray(bf16 = false))
  val arrays = Seq(bf16Array, int8Array0, int8Array1)

  val modes = RegInit(VecInit(Seq.fill(3)(false.B)))
  io.config.ready := true.B
  when(io.config.fire) { modes(io.config.bits.unit) := io.config.bits.mode }
  io.unitBusy := VecInit(arrays.map(_.io.busy))

  val selected = task.unit
  val nextSelected = io.task.bits.unit
  val nextResolvedBeats = Mux(nextSelected === 0.U, 8.U,
    Mux(io.task.bits.op === MatOp.mload && io.task.bits.buffer =/= 2.U, 4.U, 16.U))

  val storeData = MuxLookup(selected, 0.U(512.W))(Seq(
    0.U -> bf16Array.io.storeData,
    1.U -> int8Array0.io.storeData,
    2.U -> int8Array1.io.storeData))
  val startReady = MuxLookup(selected, false.B)(Seq(
    0.U -> bf16Array.io.start.ready,
    1.U -> int8Array0.io.start.ready,
    2.U -> int8Array1.io.start.ready))

  io.busy := state =/= idle
  io.done := pending
  io.task.ready := state === idle && !io.flush
  io.memResp.ready := state === waitMem
  io.memReq.valid := state === loadStore
  io.memReq.bits.addr := base + (beat << 6)
  io.memReq.bits.write := task.op === MatOp.mstore
  io.memReq.bits.data := storeData
  io.memReq.bits.mask := "hffffffffffffffff".U(64.W)

  for ((arr, idx) <- arrays.zipWithIndex) {
    arr.io.flush := io.flush
    arr.io.start.bits.op := task.op
    arr.io.start.valid := state === compute && task.op =/= MatOp.mwait && selected === idx.U
    arr.io.loadBeat.valid := state === waitMem && task.op === MatOp.mload &&
      io.memResp.valid && selected === idx.U
    arr.io.loadBeat.bits.buffer := task.buffer
    arr.io.loadBeat.bits.beat := beat
    arr.io.loadBeat.bits.data := io.memResp.bits.data
    arr.io.storeBeat := beat
  }

  when(io.flush) {
    state := idle
    pending := false.B
  }.otherwise {
    when(pending) { pending := false.B }
    when(state === idle) {
      when(io.task.fire) {
        task := io.task.bits
        beat := 0.U
        totalBeats := nextResolvedBeats
        base := io.task.bits.addr
        state := Mux(io.task.bits.op === MatOp.mload || io.task.bits.op === MatOp.mstore,
          loadStore, compute)
      }
    }
    when(state === compute) {
      when(task.op === MatOp.mwait) {
        when(MuxLookup(selected, false.B)(Seq(
          0.U -> !bf16Array.io.busy,
          1.U -> !int8Array0.io.busy,
          2.U -> !int8Array1.io.busy))) {
          pending := true.B
          state := idle
        }
      }.otherwise {
        when(startReady) {
          state := waitArray
        }
      }
    }
    when(state === loadStore) {
      when(io.memReq.fire) { state := waitMem }
    }
    when(state === waitMem) {
      when(io.memResp.fire) {
        when(beat === totalBeats - 1.U) {
          pending := true.B
          state := idle
        }.otherwise {
          beat := beat + 1.U
          state := loadStore
        }
      }
    }
    when(state === waitArray) {
      when(MuxLookup(selected, false.B)(Seq(
        0.U -> !bf16Array.io.busy,
        1.U -> !int8Array0.io.busy,
        2.U -> !int8Array1.io.busy))) {
        pending := true.B
        state := idle
      }
    }
  }
}
