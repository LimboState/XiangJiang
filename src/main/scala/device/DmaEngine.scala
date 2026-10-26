package device

import chisel3._
import chisel3.util._
import xiangjiang.XJParams

class DmaLocalRequest extends Bundle {
  val write = Bool()
  val addr = UInt(32.W)
  val data = UInt(128.W)
}

class DmaLocalResponse extends Bundle {
  val data = UInt(128.W)
  val error = Bool()
}

/** Single-burst-at-a-time host/DTCM DMA.
  * The DTCM request is deliberately part of this engine; its 128-bit beat is
  * placed directly into the selected quarter of the existing 512-bit bank row.
  */
class DmaEngine(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val axi = new DmaAxiMaster
    val localReq = Decoupled(new DmaLocalRequest)
    val localResp = Flipped(Decoupled(new DmaLocalResponse))
    val start = Input(Bool())
    val clear = Input(Bool())
    val dir = Input(Bool()) // false: host -> DTCM, true: DTCM -> host
    val hostAddr = Input(UInt(64.W))
    val localAddr = Input(UInt(32.W))
    val length = Input(UInt(32.W))
    val busy = Output(Bool())
    val done = Output(Bool())
    val error = Output(Bool())
    val errorCode = Output(UInt(4.W))
    val errorAddr = Output(UInt(64.W))
  })

  val states = Enum(8)
  val idle = states(0)
  val loadAddr = states(1)
  val loadData = states(2)
  val storeAddr = states(3)
  val storeRead = states(4)
  val storeReadWait = states(5)
  val storeData = states(6)
  val storeResp = states(7)
  val state = RegInit(idle)
  val direction = RegInit(false.B)
  val hostCur = RegInit(0.U(64.W))
  val localCur = RegInit(0.U(32.W))
  val remaining = RegInit(0.U(32.W))
  val beats = RegInit(0.U(9.W))
  val beat = RegInit(0.U(9.W))
  val failed = RegInit(false.B)
  val doneReg = RegInit(false.B)
  val errorReg = RegInit(false.B)
  val errorCodeReg = RegInit(0.U(4.W))
  val errorAddrReg = RegInit(0.U(64.W))
  val storeDataReg = RegInit(0.U(128.W))
  val storeBeatPoisoned = RegInit(false.B)
  val stalledCycles = RegInit(0.U(10.W))
  val timeoutLimit = 1023.U(10.W)

  def burstBeats(addr: UInt, bytes: UInt): UInt = {
    val bytesToPage = 4096.U(64.W) - addr(11, 0)
    val pageBeats = bytesToPage >> 4
    val requested = bytes >> 4
    Mux(requested > 256.U, Mux(pageBeats < 256.U, pageBeats, 256.U),
      Mux(pageBeats < requested, pageBeats, requested))(8, 0)
  }

  val startInvalid = io.length === 0.U || io.hostAddr(3, 0) =/= 0.U ||
    io.localAddr(3, 0) =/= 0.U || (io.length(3, 0) =/= 0.U) ||
    (io.hostAddr +& io.length) > (BigInt(1) << 64).U ||
    (io.localAddr +& io.length) > p.dtcmBytes.U

  io.busy := state =/= idle
  io.done := doneReg
  io.error := errorReg
  io.errorCode := errorCodeReg
  io.errorAddr := errorAddrReg
  io.localReq.valid := false.B
  io.localReq.bits.write := false.B
  io.localReq.bits.addr := localCur
  io.localReq.bits.data := 0.U
  when (state === loadData) {
    io.localReq.bits.addr := localCur + (beat << 4)
  }
  io.localResp.ready := state === storeReadWait
  when(state === storeRead) {
    io.localReq.bits.addr := localCur + (beat << 4)
  }

  io.axi.aw.valid := false.B
  io.axi.aw.bits := 0.U.asTypeOf(new DmaAxiAddress)
  io.axi.w.valid := false.B
  io.axi.w.bits := 0.U.asTypeOf(new DmaAxiWriteData)
  io.axi.b.ready := false.B
  io.axi.ar.valid := false.B
  io.axi.ar.bits := 0.U.asTypeOf(new DmaAxiAddress)
  io.axi.r.ready := false.B

  val progress = WireDefault(false.B)

  // CLEAR only acknowledges an idle engine; it never cancels a transfer.
  // Conflicting commands have no effect, including on sticky diagnostics.
  when (io.clear && !io.start && state === idle) {
    doneReg := false.B
    errorReg := false.B
    errorCodeReg := 0.U
    errorAddrReg := 0.U
  }

  when (io.start && !io.clear && state === idle && !errorReg) {
    doneReg := false.B
    failed := false.B
    direction := io.dir
    hostCur := io.hostAddr
    localCur := io.localAddr
    remaining := io.length
    when (startInvalid) {
      errorReg := true.B
      errorCodeReg := 1.U // descriptor alignment/range/length error
      errorAddrReg := io.hostAddr
      doneReg := false.B
    }.otherwise {
      state := Mux(io.dir, storeAddr, loadAddr)
    }
  }

  when (state === idle || progress) {
    stalledCycles := 0.U
  }.elsewhen (stalledCycles < timeoutLimit) {
    stalledCycles := stalledCycles + 1.U
  }
  when (state =/= idle && !progress && stalledCycles === timeoutLimit) {
    failed := true.B
    when (!errorReg) {
      errorReg := true.B
      errorCodeReg := 5.U
      errorAddrReg := Mux(direction, hostCur, hostCur + (beat << 4))
    }
    when (state === loadAddr || state === storeAddr) {
      state := idle
      doneReg := false.B
    }
  }

  switch (state) {
    is (loadAddr) {
      beats := burstBeats(hostCur, remaining)
      io.axi.ar.valid := true.B
      io.axi.ar.bits.id := 0.U
      io.axi.ar.bits.addr := hostCur
      io.axi.ar.bits.len := burstBeats(hostCur, remaining) - 1.U
      io.axi.ar.bits.size := 4.U
      io.axi.ar.bits.burst := 1.U
      progress := io.axi.ar.fire
      when (io.axi.ar.fire) { beat := 0.U; state := loadData }
    }
    is (loadData) {
      val last = beat === beats - 1.U
      val goodResponse = io.axi.r.bits.resp === 0.U && io.axi.r.bits.id === 0.U &&
        io.axi.r.bits.last === last
      io.localReq.valid := io.axi.r.valid && !failed && goodResponse
      io.localReq.bits.write := true.B
      io.localReq.bits.data := io.axi.r.bits.data
      io.axi.r.ready := failed || !goodResponse || io.localReq.ready
      progress := io.axi.r.fire
      when (io.axi.r.fire) {
        when (!goodResponse) {
          failed := true.B
          when (!errorReg) {
            errorReg := true.B
            errorCodeReg := 2.U // AXI response, ID or RLAST error
            errorAddrReg := hostCur + (beat << 4)
          }
        }
        when (io.axi.r.bits.last) {
          when (failed || !goodResponse || remaining === (beats << 4) || !last) {
            state := idle
            doneReg := !failed && goodResponse && last
          }.otherwise {
            hostCur := hostCur + (beats << 4)
            localCur := localCur + (beats << 4)
            remaining := remaining - (beats << 4)
            state := loadAddr
          }
        }.otherwise { beat := beat + 1.U }
      }
    }
    is (storeAddr) {
      beats := burstBeats(hostCur, remaining)
      io.axi.aw.valid := true.B
      io.axi.aw.bits.id := 0.U
      io.axi.aw.bits.addr := hostCur
      io.axi.aw.bits.len := burstBeats(hostCur, remaining) - 1.U
      io.axi.aw.bits.size := 4.U
      io.axi.aw.bits.burst := 1.U
      progress := io.axi.aw.fire
      when (io.axi.aw.fire) { beat := 0.U; state := storeRead }
    }
    is (storeRead) {
      io.localReq.valid := true.B
      io.localReq.bits.write := false.B
      progress := io.localReq.fire
      when (io.localReq.fire) { state := storeReadWait }
    }
    is (storeReadWait) {
      progress := io.localResp.fire
      when (io.localResp.fire) {
        val poisoned = failed || io.localResp.bits.error
        storeDataReg := Mux(poisoned, 0.U, io.localResp.bits.data)
        storeBeatPoisoned := poisoned
        when (io.localResp.bits.error) {
          failed := true.B
          when (!errorReg) {
            errorReg := true.B
            errorCodeReg := 3.U // local DTCM access error
            errorAddrReg := hostCur + (beat << 4)
          }
        }
        state := storeData
      }
    }
    is (storeData) {
      val last = beat === beats - 1.U
      io.axi.w.valid := true.B
      io.axi.w.bits.data := Mux(storeBeatPoisoned, 0.U, storeDataReg)
      io.axi.w.bits.strb := Mux(storeBeatPoisoned, 0.U, "hffff".U)
      io.axi.w.bits.last := last
      progress := io.axi.w.fire
      when (io.axi.w.fire) {
        when (last) { state := storeResp }
          .otherwise { beat := beat + 1.U; state := storeRead }
      }
    }
    is (storeResp) {
      io.axi.b.ready := true.B
      progress := io.axi.b.fire
      when (io.axi.b.fire) {
        when (io.axi.b.bits.resp =/= 0.U || io.axi.b.bits.id =/= 0.U) {
          failed := true.B
          when (!errorReg) {
            errorReg := true.B
            errorCodeReg := 4.U // AXI write response or ID error
            errorAddrReg := hostCur
          }
        }
        when (failed || io.axi.b.bits.resp =/= 0.U || io.axi.b.bits.id =/= 0.U || remaining === (beats << 4)) {
          state := idle
          doneReg := !failed && io.axi.b.bits.resp === 0.U && io.axi.b.bits.id === 0.U
        }.otherwise {
          hostCur := hostCur + (beats << 4)
          localCur := localCur + (beats << 4)
          remaining := remaining - (beats << 4)
          state := storeAddr
        }
      }
    }
  }
}
