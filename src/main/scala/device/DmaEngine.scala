package device

import chisel3._
import chisel3.util._
import xiangjiang._

/** Ordered DMA with independent inbound/outbound command and line buffers.
  * One burst is outstanding; its entire return space is reserved before AR.
  * No reset/flush/abort input: accepted AXI transactions must finish before
  * the core can acknowledge a fault or reuse a ROB generation.
  */
class DmaEngine(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new DmaCommand))
    val completion = Decoupled(new DmaCompletion)
    val axi = Flipped(new Axi4Slave(p.axiIdBits))
    val memReq = Decoupled(new MatrixBeatReq)
    val memResp = Flipped(Decoupled(new MatrixBeatResp))
    val busy = Output(Bool())
    val status = Output(new DmaCompletion)
  })

  val Seq(idle, validate, plan, readAddress, readData, readDrain, localWrite,
    localWriteResponse, localRead, localReadResponse, writeAddress, writeData,
    writeResponse, advance, complete) = Enum(15)
  val state = RegInit(idle)
  val occupied = RegInit(false.B)
  val inbound = Module(new Queue(new DmaCommand, 1))
  val outbound = Module(new Queue(new DmaCommand, 1))
  inbound.io.enq.valid := io.command.valid && !io.command.bits.write && !occupied
  outbound.io.enq.valid := io.command.valid && io.command.bits.write && !occupied
  inbound.io.enq.bits := io.command.bits
  outbound.io.enq.bits := io.command.bits
  io.command.ready := !occupied && Mux(io.command.bits.write, outbound.io.enq.ready, inbound.io.enq.ready)
  inbound.io.deq.ready := state === idle
  outbound.io.deq.ready := state === idle
  when(io.command.fire) { occupied := true.B }
  io.busy := occupied

  val command = Reg(new DmaCommand)
  val external = Reg(UInt(32.W))
  val local = Reg(UInt(32.W))
  val externalRow = Reg(UInt(32.W))
  val localRow = Reg(UInt(32.W))
  val row = Reg(UInt(32.W))
  val remaining = Reg(UInt(32.W))
  val beats = Reg(UInt(5.W)) // at most one 64-byte local row per burst
  val beat = Reg(UInt(5.W))
  val incoming = Reg(Vec(16, UInt(32.W)))
  val outgoing = Reg(UInt(512.W))
  val status = RegInit(0.U.asTypeOf(new DmaCompletion))
  val effect = RegInit(false.B)
  io.status := status
  io.completion.valid := state === complete
  io.completion.bits := status
  when(io.completion.fire) { state := idle; occupied := false.B }

  def fail(cause: UInt, address: UInt): Unit = {
    when(status.cause === FaultCode.none) {
      status.cause := cause
      status.address := address
      status.partial := effect
    }
  }
  when(inbound.io.deq.fire || outbound.io.deq.fire) {
    command := Mux(outbound.io.deq.fire, outbound.io.deq.bits, inbound.io.deq.bits)
    status := 0.U.asTypeOf(new DmaCompletion)
    effect := false.B
    state := validate
  }

  // Full-command preflight, before any AXI or SRAM request. Multiplication and
  // additions retain all carries; no truncated SRAM index can mask overflow.
  val rowsMinusOne = command.shape.rows - 1.U
  val externalEnd = command.external.pad(66) +
    (rowsMinusOne * command.shape.externalStride).pad(66) + command.shape.rowBytes.pad(66)
  val localEnd = command.local.pad(66) +
    (rowsMinusOne * command.shape.localStride).pad(66) + command.shape.rowBytes.pad(66)
  val badShape = command.shape.rows === 0.U || command.shape.rowBytes === 0.U ||
    command.shape.rowBytes(1, 0).orR
  val badExternal = command.external(1, 0).orR || externalEnd > (BigInt(1) << 32).U ||
    (command.shape.rows > 1.U && command.shape.externalStride(1, 0).orR)
  val badLocal = command.local(1, 0).orR || localEnd > p.dtcmBytes.U ||
    (command.shape.rows > 1.U && command.shape.localStride(1, 0).orR)
  when(state === validate) {
    when(badShape || badExternal || badLocal) {
      fail(FaultCode.dmaConfig, Mux(badLocal, command.local, command.external))
      state := complete
    }.otherwise {
      external := command.external; externalRow := command.external
      local := command.local; localRow := command.local
      row := 0.U; remaining := command.shape.rowBytes
      state := plan
    }
  }
  def min(a: UInt, b: UInt): UInt = Mux(a < b, a, b)
  val localWords = 16.U(5.W) - local(5, 2)
  val pageWords = (4096.U(13.W) - external(11, 0)) >> 2
  val burstWords = min(min(remaining >> 2, localWords), min(pageWords, p.dmaMaxBurstBeats.U))
  when(state === plan) {
    beats := burstWords
    beat := 0.U
    state := Mux(command.write, localRead, readAddress)
  }
  val burstBytes = beats << 2
  val wordIndex = (local(5, 2) + beat)(3, 0)

  io.axi.ar.valid := state === readAddress
  io.axi.aw.valid := state === writeAddress
  for (address <- Seq(io.axi.ar.bits, io.axi.aw.bits)) {
    address := 0.U.asTypeOf(new AxiAddress(p.axiIdBits))
    address.addr := external
    address.len := beats - 1.U
    address.size := 2.U
    address.burst := 1.U // INCR, ordinary nonexclusive memory
  }
  io.axi.r.ready := state === readData || state === readDrain
  io.axi.w.valid := state === writeData
  io.axi.w.bits.data := (outgoing >> Cat(wordIndex, 0.U(5.W)))(31, 0)
  io.axi.w.bits.strb := 15.U
  io.axi.w.bits.last := beat === beats - 1.U
  io.axi.b.ready := state === writeResponse

  io.memReq.valid := state === localRead || state === localWrite
  io.memReq.bits.addr := Cat(local(31, 6), 0.U(6.W))
  io.memReq.bits.write := state === localWrite
  io.memReq.bits.data := incoming.asUInt
  val byteMask = ((1.U(65.W) << burstBytes) - 1.U)(63, 0)
  io.memReq.bits.mask := (byteMask << local(5, 0))(63, 0)
  io.memResp.ready := state === localReadResponse || state === localWriteResponse

  when(io.axi.ar.fire) { state := readData }
  val expectedLast = beat === beats - 1.U
  val readProtocolError = io.axi.r.bits.id =/= 0.U || io.axi.r.bits.last =/= expectedLast
  val readError = io.axi.r.bits.resp =/= 0.U || readProtocolError
  when(state === readData && io.axi.r.fire) {
    incoming(wordIndex) := io.axi.r.bits.data
    when(readError) {
      fail(Mux(readProtocolError, FaultCode.dmaProtocol, FaultCode.dmaRead), external + (beat << 2))
    }
    when(expectedLast) {
      when(!io.axi.r.bits.last) { state := readDrain }
        .elsewhen(readError || status.cause =/= FaultCode.none) { state := complete }
        .otherwise { state := localWrite }
    }.otherwise { beat := beat + 1.U }
  }
  // A late RLAST never allows an ID to be reused while responses still arrive.
  // Early RLAST is an error, but ARLEN remains the minimum drain count.
  when(state === readDrain && io.axi.r.fire && io.axi.r.bits.last) { state := complete }
  when(state === localWrite && io.memReq.fire) { state := localWriteResponse }
  when(state === localWriteResponse && io.memResp.fire) {
    when(io.memResp.bits.error) {
      fail(FaultCode.dmaWrite, local)
      state := complete
    }.otherwise {
      effect := true.B
      status.bytesCompleted := status.bytesCompleted + burstBytes
      state := advance
    }
  }
  when(state === localRead && io.memReq.fire) { state := localReadResponse }
  when(state === localReadResponse && io.memResp.fire) {
    when(io.memResp.bits.error) {
      fail(FaultCode.dmaRead, local)
      state := complete
    }.otherwise {
      outgoing := io.memResp.bits.data
      state := writeAddress
    }
  }
  when(io.axi.aw.fire) {
    // Once AW is accepted, every W beat and the B response must be drained.
    effect := true.B
    state := writeData
  }
  when(io.axi.w.fire) {
    when(expectedLast) { state := writeResponse }
      .otherwise { beat := beat + 1.U }
  }
  when(io.axi.b.fire) {
    when(io.axi.b.bits.resp =/= 0.U || io.axi.b.bits.id =/= 0.U) {
      fail(Mux(io.axi.b.bits.id =/= 0.U, FaultCode.dmaProtocol, FaultCode.dmaWrite), external)
      state := complete
    }.otherwise {
      status.bytesCompleted := status.bytesCompleted + burstBytes
      state := advance
    }
  }
  when(state === advance) {
    when(remaining === burstBytes) {
      when(row === command.shape.rows - 1.U) { state := complete }
        .otherwise {
          row := row + 1.U
          externalRow := externalRow + command.shape.externalStride
          localRow := localRow + command.shape.localStride
          external := externalRow + command.shape.externalStride
          local := localRow + command.shape.localStride
          remaining := command.shape.rowBytes
          state := plan
        }
    }.otherwise {
      external := external + burstBytes
      local := local + burstBytes
      remaining := remaining - burstBytes
      state := plan
    }
  }
}
