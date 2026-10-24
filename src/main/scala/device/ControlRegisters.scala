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
    val dmaBusy = Input(Bool())
    val dmaDone = Input(Bool())
    val dmaError = Input(Bool())
    val dmaErrorCode = Input(UInt(4.W))
    val dmaErrorAddr = Input(UInt(64.W))
    val start = Output(Bool())
    val ack = Output(Bool())
    val dmaStart = Output(Bool())
    val dmaClear = Output(Bool())
    val dmaDirection = Output(Bool())
    val dmaHostAddr = Output(UInt(64.W))
    val dmaLocalAddr = Output(UInt(32.W))
    val dmaLength = Output(UInt(32.W))
    val debugRegs = Input(Vec(32, UInt(32.W)))
  })
  val awValid = RegInit(false.B)
  val aw = Reg(new LiteAddress)
  val wValid = RegInit(false.B)
  val w = Reg(new LiteWriteData)
  val bValid = RegInit(false.B)
  val b = Reg(UInt(2.W))
  val dmaHostAddrLowReg = RegInit(0.U(32.W))
  val dmaHostAddrHighReg = RegInit(0.U(32.W))
  val dmaLocalAddrReg = RegInit(0.U(32.W))
  val dmaLengthReg = RegInit(0.U(32.W))
  val dmaDirectionReg = RegInit(false.B)
  val dmaWriteMask = Cat((0 until 4).reverse.map(lane => Fill(8, w.strb(lane))))
  def mergeStrobedWrite(oldValue: UInt): UInt =
    (oldValue & ~dmaWriteMask) | (w.data & dmaWriteMask)
  io.axi.aw.ready := !awValid && !bValid
  io.axi.w.ready := !wValid && !bValid
  when(io.axi.aw.fire) { aw := io.axi.aw.bits; awValid := true.B }
  when(io.axi.w.fire) { w := io.axi.w.bits; wValid := true.B }
  io.axi.b.valid := bValid
  io.axi.b.bits := b
  when(io.axi.b.fire) { bValid := false.B }

  io.start := false.B
  io.ack := false.B
  io.dmaStart := false.B
  io.dmaClear := false.B
  io.dmaDirection := dmaDirectionReg
  io.dmaHostAddr := Cat(dmaHostAddrHighReg, dmaHostAddrLowReg)
  io.dmaLocalAddr := dmaLocalAddrReg.asUInt
  io.dmaLength := dmaLengthReg.asUInt
  val requestStart = aw.addr === 0.U && w.strb(0) && w.data(0)
  val requestAck = aw.addr === 0.U && w.strb(0) && w.data(1)
  val allowedStart = !io.status.busy && !io.status.done && !io.status.fault && io.hostIdle
  val allowedAck = !io.status.busy
  val dmaAddress = aw.addr === "h80".U || aw.addr === "h84".U || aw.addr === "h88".U ||
    aw.addr === "h8c".U || aw.addr === "h90".U
  val dmaConfigWrite = dmaAddress && !requestStart
  val dmaStartRequest = aw.addr === "h90".U && w.strb(0) && w.data(0)
  val writeError = (!dmaAddress && aw.addr =/= 0.U) ||
    (requestStart && requestAck) || (requestStart && !allowedStart) ||
    (requestAck && !allowedAck) ||
    (dmaConfigWrite && (io.status.busy || io.dmaBusy)) ||
    (dmaStartRequest && (io.status.busy || io.dmaBusy || !io.hostIdle))
  when(awValid && wValid && !bValid) {
    awValid := false.B
    wValid := false.B
    bValid := true.B
    b := Mux(writeError, 2.U, 0.U)
    io.start := !writeError && requestStart
    io.ack := !writeError && requestAck
    io.dmaStart := !writeError && dmaStartRequest
    io.dmaClear := !writeError && aw.addr === "h90".U && w.strb(0) && w.data(1)
    when(!writeError && dmaConfigWrite && aw.addr === "h80".U) {
      dmaHostAddrLowReg := mergeStrobedWrite(dmaHostAddrLowReg)
    }
    when(!writeError && dmaConfigWrite && aw.addr === "h84".U) {
      dmaHostAddrHighReg := mergeStrobedWrite(dmaHostAddrHighReg)
    }
    when(!writeError && dmaConfigWrite && aw.addr === "h88".U) {
      dmaLocalAddrReg := mergeStrobedWrite(dmaLocalAddrReg)
    }
    when(!writeError && dmaConfigWrite && aw.addr === "h8c".U) {
      dmaLengthReg := mergeStrobedWrite(dmaLengthReg)
    }
    when(!writeError && aw.addr === "h90".U && w.strb(0)) { dmaDirectionReg := w.data(2) }
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
    is("h80".U) { readData := dmaHostAddrLowReg }
    is("h84".U) { readData := dmaHostAddrHighReg }
    is("h88".U) { readData := dmaLocalAddrReg }
    is("h8c".U) { readData := dmaLengthReg }
    is("h90".U) { readData := Cat(0.U(29.W), io.dmaError, io.dmaBusy, io.dmaDirection) }
    is("h94".U) { readData := Cat(0.U(28.W), io.dmaErrorCode) }
    is("h98".U) { readData := io.dmaErrorAddr(31, 0) }
    is("h9c".U) { readData := io.dmaErrorAddr(63, 32) }
    is("ha0".U) { readData := Cat(0.U(31.W), io.dmaDone) }
  }
  known := (address <= "h30".U) || (address >= "h80".U && address <= "ha0".U) || (address >= "h100".U && address <= "h17c".U)
  when(address >= "h100".U && address <= "h17c".U) {
    readData := io.debugRegs(address(6, 2))
  }
  when(io.axi.ar.fire) {
    rValid := true.B
    r.data := readData
    r.resp := Mux(known && address(1, 0) === 0.U, 0.U, 2.U)
  }
}
