package top

import chisel3._
import chisel3.util._
import device.{Axi4Slave, AxiLiteSlave, ControlRegisters, MemorySubsystem}
import xiangjiang._

/** XiangJiang scalar/vector NPU with separate host control and memory AXI slaves. */
class XiangJiangNPU(val p: XJParams = XJParams()) extends Module {
  val io = IO(new Bundle {
    val control = new AxiLiteSlave
    val memory = new Axi4Slave(p.axiIdBits)
    val external = Flipped(new Axi4Slave(p.axiIdBits))
    val status = Output(new CoreStatus)
    val retire = Output(Valid(new CommitRecord(p)))
    val debugRegs = Output(Vec(32, UInt(32.W)))
    val debugVRegs = Output(Vec(32, UInt(p.vectorBits.W)))
  })

  val core = Module(new ScalarCore(p))
  val tcm = Module(new MemorySubsystem(p))
  val control = Module(new ControlRegisters(p))
  io.control <> control.io.axi
  io.memory <> tcm.io.axi
  io.external <> core.io.external
  // Accepted START is a one-cycle registered command. Reserve the memory and
  // status interface during that cycle so no host transaction can slip between
  // the idle check and the core becoming busy. Acceptance itself still checks
  // live host channel activity in ControlRegisters/MemorySubsystem.
  val startPending = RegNext(control.io.start, false.B)
  val visibleStatus = WireDefault(core.io.status)
  visibleStatus.busy := core.io.status.busy || startPending
  control.io.status := visibleStatus
  control.io.hostIdle := tcm.io.hostIdle
  control.io.debugRegs := core.io.debugRegs
  core.io.start := startPending
  core.io.ack := control.io.ack
  tcm.io.coreBusy := visibleStatus.busy
  tcm.io.busPoisoned := core.io.status.dma.poisoned
  tcm.io.fetchReq <> core.io.fetchReq
  core.io.fetchResp := tcm.io.fetchResp
  tcm.io.dataReq <> core.io.dataReq
  core.io.dataResp <> tcm.io.dataResp
  tcm.io.matrixReq <> core.io.matrixReq
  core.io.matrixResp <> tcm.io.matrixResp
  io.status := visibleStatus
  io.retire := core.io.retire
  io.debugRegs := core.io.debugRegs
  io.debugVRegs := core.io.debugVRegs
}
