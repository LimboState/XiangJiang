package xiangjiang.frontend

import chisel3._
import chisel3.util._
import xiangjiang._

/** Non-predicting, single-outstanding synchronous ITCM frontend. */
class Frontend extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val stopRun = Input(Bool())
    val barrier = Input(Bool())
    val redirect = Input(Valid(UInt(32.W)))
    val request = Decoupled(UInt(32.W))
    val response = Input(Valid(new FetchPacket))
    val fetch = Decoupled(new FetchPacket)
  })

  val running = RegInit(false.B)
  val paused = RegInit(false.B)
  val pending = RegInit(false.B)
  val valid = RegInit(false.B)
  val pc = RegInit(0.U(32.W))
  val packet = Reg(new FetchPacket)

  io.request.valid := running && !paused && !pending && !valid && !io.stopRun
  io.request.bits := pc
  io.fetch.valid := valid && running && !io.stopRun
  io.fetch.bits := packet

  when(io.request.fire) {
    pending := true.B
    pc := pc + 4.U
  }
  when(io.response.valid && pending && running) {
    packet := io.response.bits
    valid := true.B
    pending := false.B
  }
  when(io.fetch.fire) {
    valid := false.B
    when(io.barrier) { paused := true.B }
  }
  // The retained barrier packet blocks further fetch even before dispatch has room.
  when(valid && io.barrier) { paused := true.B }
  when(io.redirect.valid) {
    pc := io.redirect.bits
    paused := false.B
    valid := false.B
    pending := false.B
  }
  when(io.start) {
    running := true.B
    paused := false.B
    pending := false.B
    valid := false.B
    pc := 0.U
  }
  when(io.stopRun) {
    running := false.B
    valid := false.B
    pending := false.B
    paused := false.B
  }
}
