package xiangjiang.mem

import chisel3._
import chisel3.util._
import device.Axi4Slave
import xiangjiang._

/** Ordered, synchronous 2-D DMA. One buffered burst at a time (<=4 outstanding).
  * A complete 256-byte buffer is reserved before AR or AW. Read errors drain
  * the entire accepted burst without publishing it; AW commits us to all W
  * beats and B. No flush input can cancel a transaction. Configuration is
  * held privately until a matching ROB commit; ACK only clears progress.
  */
class DmaEngine(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new IssueEntry(p)))
    val result = Decoupled(new ExeResult(p))
    val commit = Input(Valid(new RobTag(p)))
    val ack = Input(Bool())
    val axi = Flipped(new Axi4Slave(p.axiIdBits))
    val localReq = Decoupled(new WordReq)
    val localResp = Flipped(Decoupled(new WordResp))
    val status = Output(new DmaStatus)
    val busy = Output(Bool())
    val fault = Output(new ExeResult(p))
  })
  val Seq(idle, check, prepare, ar, receive, localWrite, localWriteWait,
    localRead, localReadWait, aw, send, response, advance, complete, publish) = Enum(15)
  val state = RegInit(idle)
  val owner = Reg(new IssueEntry(p))
  val result = RegInit(0.U.asTypeOf(new ExeResult(p)))
  val rowBytes = RegInit(0.U(32.W))
  val rows = RegInit(0.U(32.W))
  val extStride = RegInit(0.U(32.W))
  val localStride = RegInit(0.U(32.W))
  val sizeValid = RegInit(false.B)
  val strideValid = RegInit(false.B)
  val bytesDone = RegInit(0.U(32.W))
  val rowsDone = RegInit(0.U(32.W))
  val error = RegInit(false.B)
  val poisoned = RegInit(false.B)
  val direction = RegInit(false.B)
  val extRow = Reg(UInt(32.W))
  val localRow = Reg(UInt(32.W))
  val offset = Reg(UInt(32.W))
  val row = Reg(UInt(32.W))
  val ext = Reg(UInt(32.W))
  val local = Reg(UInt(32.W))
  val beats = Reg(UInt(7.W))
  val beat = Reg(UInt(6.W))
  val buffer = Reg(Vec(64, UInt(32.W)))
  val burstError = RegInit(false.B)
  val isConfig = owner.uop.op === UOpCode.DSETSIZE || owner.uop.op === UOpCode.DSETSTRIDE
  val active = state =/= idle && !isConfig
  io.command.ready := state === idle && !poisoned
  io.result.valid := state === complete && !poisoned
  io.result.bits := result
  io.fault := result
  io.busy := (state =/= idle && state =/= publish) || poisoned
  io.status.rowBytes := rowBytes
  io.status.rows := rows
  io.status.externalStride := extStride
  io.status.localStride := localStride
  io.status.bytesDone := bytesDone
  io.status.rowsDone := rowsDone
  io.status.active := active || poisoned
  io.status.outbound := direction
  io.status.error := error
  io.status.poisoned := poisoned

  io.axi.ar.valid := state === ar && !poisoned
  io.axi.aw.valid := state === aw && !poisoned
  for (addr <- Seq(io.axi.ar.bits, io.axi.aw.bits)) {
    addr := 0.U.asTypeOf(addr)
    addr.addr := ext
    addr.len := beats - 1.U
    addr.size := 2.U
    addr.burst := 1.U
  }
  io.axi.r.ready := state === receive && !poisoned
  // Even if another channel poisons the bus, fulfill an already accepted AW.
  io.axi.w.valid := state === send
  io.axi.w.bits.data := buffer(beat)
  io.axi.w.bits.strb := 15.U
  io.axi.w.bits.last := beat === beats - 1.U
  io.axi.b.ready := state === response && !poisoned
  io.localReq.valid := (state === localRead || state === localWrite) && !poisoned
  io.localReq.bits.addr := local + (beat << 2)
  io.localReq.bits.write := state === localWrite
  io.localReq.bits.data := buffer(beat)
  io.localReq.bits.mask := 15.U
  // Accepted local transactions also drain after protocol poison.
  io.localResp.ready := state === localReadWait || state === localWriteWait

  def fail(cause: UInt, addr: UInt, reason: UInt): Unit = {
    when(!error) {
      result.exception := true.B
      result.cause := cause
      result.badAddr := addr
      result.detail := Cat(3.U(8.W), reason.pad(8)(7, 0), row(15, 0))
    }
    error := true.B
  }
  when(io.ack && state === idle && !poisoned) {
    bytesDone := 0.U; rowsDone := 0.U; error := false.B; direction := false.B
    result := 0.U.asTypeOf(new ExeResult(p))
  }
  when(io.command.fire) {
    owner := io.command.bits
    result := 0.U.asTypeOf(new ExeResult(p))
    result.tag := io.command.bits.uop.tag
    result.seq := io.command.bits.uop.seq
    result.nextPc := io.command.bits.uop.pc + 4.U
    error := false.B
    row := 0.U; offset := 0.U
    extRow := io.command.bits.src1.value
    localRow := io.command.bits.src2.value
    when(io.command.bits.uop.op === UOpCode.MVIN || io.command.bits.uop.op === UOpCode.MVOUT) {
      bytesDone := 0.U; rowsDone := 0.U
      direction := io.command.bits.uop.op === UOpCode.MVOUT
    }
    state := check
  }
  val a = owner.src1.value
  val b = owner.src2.value
  // Multiplication is 64 bits; all subsequent additions retain the 65th bit.
  val lastRow = rows - 1.U
  val extLast = a.pad(65) + (lastRow * extStride).pad(65) + rowBytes.pad(65) - 1.U
  val localLast = b.pad(65) + (lastRow * localStride).pad(65) + rowBytes.pad(65) - 1.U
  when(state === check) {
    when(isConfig) {
      val size = owner.uop.op === UOpCode.DSETSIZE
      val badA = a(1, 0).orR || (size && a === 0.U)
      val badB = Mux(size, b === 0.U, b(1, 0).orR)
      when(badA || badB) { fail(FaultCode.dmaConfig, Mux(badA, a, b), 1.U) }
      state := complete
    }.otherwise {
      val misaligned = a(1, 0).orR || b(1, 0).orR
      val missing = !sizeValid || !strideValid
      val outExt = extLast >= (BigInt(1) << 32).U(65.W)
      val outLocal = localLast >= p.dtcmBytes.U(65.W)
      when(misaligned || missing || outExt || outLocal) {
        val addr = Mux(misaligned, Mux(a(1, 0).orR, a, b),
          Mux(missing, 0.U, Mux(outExt, a, b)))
        fail(FaultCode.dmaConfig, addr, Mux(misaligned, 2.U, Mux(missing, 3.U, 4.U)))
        state := complete
      }.otherwise { state := prepare }
    }
  }
  val nextExt = extRow + offset
  val remaining = rowBytes - offset
  val pageRemaining = 4096.U(13.W) - nextExt(11, 0)
  val capped = Mux(remaining > 256.U, 256.U, remaining)
  val chunk = Mux(capped > pageRemaining, pageRemaining, capped)
  when(state === prepare) {
    ext := nextExt; local := localRow + offset
    beats := chunk >> 2
    beat := 0.U; burstError := false.B
    state := Mux(direction, localRead, ar)
  }
  when(io.axi.ar.fire) { state := receive }
  when(io.axi.r.fire) {
    buffer(beat) := io.axi.r.bits.data
    when(io.axi.r.bits.resp =/= 0.U) {
      fail(FaultCode.dmaRead, ext, io.axi.r.bits.resp)
      burstError := true.B
    }
    when(beat === beats - 1.U) {
      beat := 0.U
      state := Mux(burstError || io.axi.r.bits.resp =/= 0.U, complete, localWrite)
    }.otherwise { beat := beat + 1.U }
  }
  when(io.localReq.fire) { state := Mux(direction, localReadWait, localWriteWait) }
  when(io.localResp.fire) {
    when(io.localResp.bits.error) {
      fail(FaultCode.dmaConfig, local + (beat << 2), 5.U)
      state := complete
    }.otherwise {
      when(direction) { buffer(beat) := io.localResp.bits.data }
        .otherwise { bytesDone := bytesDone + 4.U }
      when(beat === beats - 1.U) {
        beat := 0.U
        state := Mux(direction, aw, advance)
      }.otherwise {
        beat := beat + 1.U
        state := Mux(direction, localRead, localWrite)
      }
    }
  }
  when(io.axi.aw.fire) { state := send }
  when(io.axi.w.fire) {
    when(beat === beats - 1.U) { state := response }.otherwise { beat := beat + 1.U }
  }
  when(io.axi.b.fire) {
    when(io.axi.b.bits.resp =/= 0.U) {
      fail(FaultCode.dmaWrite, ext, io.axi.b.bits.resp)
      state := complete
    }.otherwise {
      bytesDone := bytesDone + (beats << 2)
      state := advance
    }
  }
  when(state === advance) {
    when(offset + (beats << 2) === rowBytes) {
      rowsDone := rowsDone + 1.U
      when(row === rows - 1.U) { state := complete }.otherwise {
        row := row + 1.U; offset := 0.U
        extRow := extRow + extStride; localRow := localRow + localStride
        state := prepare
      }
    }.otherwise { offset := offset + (beats << 2); state := prepare }
  }
  when(io.result.fire) { state := Mux(isConfig && !result.exception, publish, idle) }
  when(state === publish && io.commit.valid && TagMatch(io.commit.bits, owner.uop.tag)) {
    when(owner.uop.op === UOpCode.DSETSIZE) { rowBytes := a; rows := b; sizeValid := true.B }
      .otherwise { extStride := a; localStride := b; strideValid := true.B }
    state := idle
  }
  // A response may be asserted on the address handshake cycle, but it cannot
  // be consumed until the owned receive state. Other unsolicited responses,
  // wrong IDs and either early or missing LAST destroy ownership proof.
  val badR = io.axi.r.valid && (io.axi.r.bits.id =/= 0.U ||
    !(state === receive || (state === ar && io.axi.ar.fire)) ||
    (state === receive && io.axi.r.bits.last =/= (beat === beats - 1.U)))
  val badB = io.axi.b.valid && (io.axi.b.bits.id =/= 0.U ||
    !(state === response || (state === send && io.axi.w.fire && io.axi.w.bits.last)))
  when((badR || badB) && !poisoned) {
    poisoned := true.B
    error := true.B
    result.exception := true.B
    result.cause := FaultCode.dmaProtocol
    result.badAddr := Mux(state === idle, 0.U, ext)
    result.detail := Cat(3.U(8.W), 6.U(8.W), row(15, 0))
  }
}
