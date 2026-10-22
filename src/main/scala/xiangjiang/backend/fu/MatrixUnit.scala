package xiangjiang.backend.fu

import chisel3._
import chisel3.util._
import xiangjiang._

/** One 16x16 matrix array with plain register-based input buffers and an
  * internal accumulator.  The staircase/skewed datapath of the architecture is
  * folded into the load/compute handshake: MLOAD always writes a complete
  * 16x16 tile before the array can start a compute task, and the per-element
  * systolic reduction below produces the same architectural result.
  */
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

class MatrixArray(bf16: Boolean) extends Module {
  val io = IO(new MatrixArrayIO)
  private val idle :: computing :: complete :: Nil = Enum(3)
  private val state = RegInit(idle)
  private val op = Reg(MatOp())
  private val phase = Reg(UInt(8.W))

  if (bf16) {
    val aBuf = Reg(Vec(256, UInt(16.W)))
    val bBuf = Reg(Vec(256, UInt(16.W)))
    val acc = Reg(Vec(256, UInt(32.W)))

    when(io.loadBeat.valid) {
      val beat = io.loadBeat.bits.beat
      val data = io.loadBeat.bits.data
      when(io.loadBeat.bits.buffer === 0.U) {
        aBuf := VecInit((0 until 256).map { e =>
          val local = e % 32
          Mux((e >> 5).U === beat, data(16 * local + 15, 16 * local), aBuf(e))
        })
      }.elsewhen(io.loadBeat.bits.buffer === 1.U) {
        bBuf := VecInit((0 until 256).map { e =>
          val local = e % 32
          Mux((e >> 5).U === beat, data(16 * local + 15, 16 * local), bBuf(e))
        })
      }.otherwise {
        acc := VecInit((0 until 256).map { e =>
          val local = e % 32
          Mux((e >> 5).U === beat,
            VectorFloat.widen(data(16 * local + 15, 16 * local)), acc(e))
        })
      }
    }

    io.storeData := VecInit((0 until 32).map { local =>
      val e = Cat(io.storeBeat(2, 0), local.U(5.W))
      VectorFloat.narrow(acc(e))
    }).asUInt

    io.start.ready := state === idle && !io.flush
    io.busy := state =/= idle
    when(io.flush) {
      state := idle
    }.otherwise {
      when(io.start.fire) {
        op := io.start.bits.op
        phase := 0.U
        state := computing
      }
      when(state === computing) {
        val i = phase(7, 4)
        val j = phase(3, 0)
        val terms = (0 until 16).map { k =>
          VectorFloat.mul32(VectorFloat.widen(aBuf(Cat(i, k.U(4.W)))),
            VectorFloat.widen(bBuf(Cat(k.U(4.W), j))))
        }
        val dot = terms.reduce((x, y) => VectorFloat.add32(x, y))
        val result = WireDefault(dot)
        when(op === MatOp.mmacc) { result := VectorFloat.add32(acc(phase), dot) }
        when(op === MatOp.mew) {
          result := VectorFloat.mul32(VectorFloat.widen(aBuf(phase)),
            VectorFloat.widen(bBuf(phase)))
        }
        when(op === MatOp.mtrans) { result := VectorFloat.widen(aBuf(Cat(j, i))) }
        acc(phase) := result
        when(phase === 255.U) { state := complete }.otherwise { phase := phase + 1.U }
      }
      when(state === complete) { state := idle }
    }
  } else {
    val aBuf = Reg(Vec(256, UInt(8.W)))
    val bBuf = Reg(Vec(256, UInt(8.W)))
    val acc = Reg(Vec(256, UInt(32.W)))

    when(io.loadBeat.valid) {
      val beat = io.loadBeat.bits.beat
      val data = io.loadBeat.bits.data
      when(io.loadBeat.bits.buffer === 0.U) {
        aBuf := VecInit((0 until 256).map { e =>
          val local = e % 64
          Mux((e >> 6).U === beat, data(8 * local + 7, 8 * local), aBuf(e))
        })
      }.elsewhen(io.loadBeat.bits.buffer === 1.U) {
        bBuf := VecInit((0 until 256).map { e =>
          val local = e % 64
          Mux((e >> 6).U === beat, data(8 * local + 7, 8 * local), bBuf(e))
        })
      }.otherwise {
        acc := VecInit((0 until 256).map { e =>
          val local = e % 16
          Mux((e >> 4).U === beat, data(32 * local + 31, 32 * local), acc(e))
        })
      }
    }

    io.storeData := VecInit((0 until 16).map { local =>
      acc(Cat(io.storeBeat(3, 0), local.U(4.W)))
    }).asUInt

    io.start.ready := state === idle && !io.flush
    io.busy := state =/= idle
    when(io.flush) {
      state := idle
    }.otherwise {
      when(io.start.fire) {
        op := io.start.bits.op
        phase := 0.U
        state := computing
      }
      when(state === computing) {
        val i = phase(7, 4)
        val j = phase(3, 0)
        val dot = (0 until 16).foldLeft(0.S(32.W)) { case (sum, k) =>
          sum + (aBuf(Cat(i, k.U(4.W))).asSInt * bBuf(Cat(k.U(4.W), j)).asSInt)
        }
        val result = WireDefault(dot.asUInt)
        when(op === MatOp.mmacc) { result := (acc(phase).asSInt + dot).asUInt }
        when(op === MatOp.mew) {
          result := (aBuf(phase).asSInt * bBuf(phase).asSInt).pad(32).asUInt
        }
        when(op === MatOp.mtrans) { result := aBuf(Cat(j, i)).asSInt.pad(32).asUInt }
        acc(phase) := result
        when(phase === 255.U) { state := complete }.otherwise { phase := phase + 1.U }
      }
      when(state === complete) { state := idle }
    }
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
