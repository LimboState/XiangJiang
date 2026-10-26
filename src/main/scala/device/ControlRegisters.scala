package device

import chisel3._
import chisel3.util._
import xiangjiang._

/** AXI4-Lite control plane, independent of the host SRAM transaction channels. */
class ControlRegisters(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val axi = new AxiLiteSlave
    val status = Input(new CoreStatus)
    val hostIdle = Input(Bool())
    val dmaStatus = Input(new DmaCompletion)
    val start = Output(Bool())
    val ack = Output(Bool())
    val debugRegs = Input(Vec(32, UInt(32.W)))
  })
  val awValid = RegInit(false.B)
  val aw = Reg(new LiteAddress)
  val wValid = RegInit(false.B)
  val w = Reg(new LiteWriteData)
  val bValid = RegInit(false.B)
  val b = Reg(UInt(2.W))
  io.axi.aw.ready := !awValid && !bValid
  io.axi.w.ready := !wValid && !bValid
  when(io.axi.aw.fire) { aw := io.axi.aw.bits; awValid := true.B }
  when(io.axi.w.fire) { w := io.axi.w.bits; wValid := true.B }
  io.axi.b.valid := bValid
  io.axi.b.bits := b
  when(io.axi.b.fire) { bValid := false.B }

  io.start := false.B
  io.ack := false.B
  val requestStart = w.strb(0) && w.data(0)
  val requestAck = w.strb(0) && w.data(1)
  val allowedStart = !io.status.busy && !io.status.done && !io.status.fault && io.hostIdle
  val allowedAck = !io.status.busy
  val writeError = aw.addr =/= 0.U ||
    (requestStart && requestAck) || (requestStart && !allowedStart) ||
    (requestAck && !allowedAck)
  when(awValid && wValid && !bValid) {
    awValid := false.B
    wValid := false.B
    bValid := true.B
    b := Mux(writeError, 2.U, 0.U)
    io.start := !writeError && requestStart
    io.ack := !writeError && requestAck
  }

  val rValid = RegInit(false.B)
  val r = Reg(new LiteReadData)
  io.axi.ar.ready := !rValid
  io.axi.r.valid := rValid
  io.axi.r.bits := r
  when(io.axi.r.fire) { rValid := false.B }
  val address = io.axi.ar.bits.addr
  val readData = WireDefault(0.U(32.W))
  val known = WireDefault(true.B)
  switch(address) {
    is("h00".U) { readData := 0.U }
    is("h04".U) { readData := Cat(io.status.fault, io.status.done, io.status.busy) }
    is("h08".U) { readData := io.status.faultPc }
    is("h0c".U) { readData := io.status.faultInst }
    is("h10".U) { readData := io.status.faultCause }
    is("h14".U) { readData := io.status.faultAddr }
    is("h18".U) { readData := p.itcmBytes.U }
    is("h1c".U) { readData := p.dtcmBytes.U }
    is("h20".U) { readData := p.nRob.U }
    is("h24".U) { readData := p.nXRes.U }
    is("h28".U) { readData := p.nMemq.U }
    is("h2c".U) { readData := p.nIssue.U }
    is("h30".U) { readData := 1.U }
    is("h34".U) { readData := Cat(io.dmaStatus.partial, io.dmaStatus.cause) }
    is("h38".U) { readData := io.dmaStatus.address }
    is("h3c".U) { readData := io.dmaStatus.bytesCompleted(31, 0) }
    is("h40".U) { readData := io.dmaStatus.bytesCompleted(63, 32) }
  }
  known := address <= "h40".U || (address >= "h100".U && address <= "h17c".U)
  when(address >= "h100".U && address <= "h17c".U) {
    readData := io.debugRegs(address(6, 2))
  }
  when(io.axi.ar.fire) {
    rValid := true.B
    r.data := readData
    r.resp := Mux(known && address(1, 0) === 0.U, 0.U, 2.U)
  }
}
