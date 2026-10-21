package top

import chisel3._
import chisel3.util._
import device.{Axi4Slave, AxiLiteSlave, ControlRegisters, MemorySubsystem}
import xiangjiang._

/** Scalar-only XiangJiang NPU with separate host control and memory AXI slaves. */
class XiangJiangNPU(val p: XJParams = XJParams()) extends Module {
  val io = IO(new Bundle {
    val control = new AxiLiteSlave
    val memory = new Axi4Slave(p.axiIdBits)
    val status = Output(new CoreStatus)
    val retire = Output(Valid(new CommitRecord(p)))
    val debugRegs = Output(Vec(32, UInt(32.W)))
  })

  val core = Module(new ScalarCore(p))
  val tcm = Module(new MemorySubsystem(p))
  val control = Module(new ControlRegisters(p))
  io.control <> control.io.axi
  io.memory <> tcm.io.axi
  control.io.status := core.io.status
  control.io.hostIdle := tcm.io.hostIdle
  control.io.debugRegs := core.io.debugRegs
  core.io.start := control.io.start
  core.io.ack := control.io.ack
  tcm.io.coreBusy := core.io.status.busy
  tcm.io.fetchReq <> core.io.fetchReq
  core.io.fetchResp := tcm.io.fetchResp
  tcm.io.dataReq <> core.io.dataReq
  core.io.dataResp <> tcm.io.dataResp
  io.status := core.io.status
  io.retire := core.io.retire
  io.debugRegs := core.io.debugRegs
}
