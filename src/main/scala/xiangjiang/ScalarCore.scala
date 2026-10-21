package xiangjiang

import chisel3._
import chisel3.util._
import xiangjiang.backend.decode.DecodeUnit
import xiangjiang.backend.fu.{ALUUnit, BranchUnit}
import xiangjiang.backend.issue.IssueUnit
import xiangjiang.backend.rename.RenameStage
import xiangjiang.backend.rob.Rob
import xiangjiang.frontend.Frontend
import xiangjiang.mem.LSU

/** Single-dispatch scalar core: value renaming, multiple FU issue, ordered commit. */
class ScalarCore(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val ack = Input(Bool())
    val fetchReq = Decoupled(UInt(32.W))
    val fetchResp = Input(Valid(new FetchPacket))
    val dataReq = Decoupled(new WordReq)
    val dataResp = Flipped(Decoupled(new WordResp))
    val status = Output(new CoreStatus)
    val debugRegs = Output(Vec(32, UInt(32.W)))
    val retire = Output(Valid(new CommitRecord(p)))
    val writeback = Output(Valid(new ExeResult(p)))
    val aluIssued = Output(Valid(new RobTag(p)))
    val aguIssued = Output(Valid(new RobTag(p)))
  })

  val frontend = Module(new Frontend)
  val decoder = Module(new DecodeUnit(p))
  val rename = Module(new RenameStage(p))
  val rob = Module(new Rob(p))
  val aluIssue = Module(new IssueUnit(p))
  val branchIssue = Module(new IssueUnit(p, headOnly = true))
  val alu = Module(new ALUUnit(p))
  val branch = Module(new BranchUnit(p))
  val lsu = Module(new LSU(p))

  val running = RegInit(false.B)
  val draining = RegInit(false.B)
  val done = RegInit(false.B)
  val fault = RegInit(false.B)
  val faultPc = RegInit(0.U(32.W))
  val faultInst = RegInit(0.U(32.W))
  val faultCause = RegInit(0.U(4.W))
  val faultAddr = RegInit(0.U(32.W))
  val systemValid = RegInit(false.B)
  val systemSent = RegInit(false.B)
  val systemResult = Reg(new ExeResult(p))

  val start = io.start && !running && !done && !fault
  val faultDetected = running && !draining && rob.io.retire.valid && rob.io.retire.bits.exception
  val stopIssue = !running || draining || faultDetected
  val quiescent = !alu.io.busy && !branch.io.busy && lsu.io.quiescent && !systemValid
  val finishFault = draining && quiescent
  val flush = start || finishFault
  // Fault cleanup discards unissued reservations after completion sources drain.
  rob.io.quiescent := quiescent
  rob.io.flush := flush
  rename.io.flush := flush
  aluIssue.io.flush := flush
  branchIssue.io.flush := flush
  alu.io.flush := flush
  branch.io.flush := flush
  lsu.io.flush := flush
  lsu.io.stop := stopIssue

  io.fetchReq <> frontend.io.request
  frontend.io.response := io.fetchResp
  frontend.io.start := start
  frontend.io.stopRun := !running && !start || draining || faultDetected
  decoder.io.in := frontend.io.fetch.bits
  frontend.io.barrier := decoder.io.out.barrier
  frontend.io.redirect.valid := false.B
  frontend.io.redirect.bits := 0.U

  val uop = WireDefault(decoder.io.out)
  uop.tag := rob.io.allocTag
  uop.seq := rob.io.allocSeq
  rename.io.uop := uop
  rob.io.query := rename.io.lookupTags
  rename.io.lookupValues := rob.io.values
  io.debugRegs := rename.io.debugRegs

  val isAlu = uop.fu === FuType.alu && !uop.exception
  val isBranch = uop.fu === FuType.bru && !uop.exception
  val isMemory = uop.fu === FuType.mem && !uop.exception
  val targetReady = (!isAlu || aluIssue.io.dispatch.ready) &&
    (!isBranch || branchIssue.io.dispatch.ready) && (!isMemory || lsu.io.allocate.ready)
  val dispatch = frontend.io.fetch.valid && !stopIssue && !flush && rob.io.allocate.ready && targetReady
  frontend.io.fetch.ready := !stopIssue && !flush && rob.io.allocate.ready && targetReady
  // Each participant observes valid only when the entire allocation can succeed.
  rob.io.allocate.valid := dispatch
  rob.io.allocate.bits := uop
  rename.io.dispatch := dispatch
  aluIssue.io.dispatch.valid := dispatch && isAlu
  aluIssue.io.dispatch.bits := rename.io.renamed
  branchIssue.io.dispatch.valid := dispatch && isBranch
  branchIssue.io.dispatch.bits := rename.io.renamed
  lsu.io.allocate.valid := dispatch && isMemory
  lsu.io.allocate.bits := rename.io.renamed

  for (issue <- Seq(aluIssue, branchIssue)) {
    issue.io.head.valid := rob.io.head.valid
    issue.io.head.bits := rob.io.head.bits.tag
  }
  lsu.io.head.valid := rob.io.head.valid
  lsu.io.head.bits := rob.io.head.bits.tag
  alu.io.req.valid := aluIssue.io.issue.valid && !stopIssue
  alu.io.req.bits := aluIssue.io.issue.bits
  aluIssue.io.issue.ready := alu.io.req.ready && !stopIssue
  branch.io.req.valid := branchIssue.io.issue.valid && !stopIssue
  branch.io.req.bits := branchIssue.io.issue.bits
  branchIssue.io.issue.ready := branch.io.req.ready && !stopIssue
  io.aluIssued.valid := alu.io.req.fire
  io.aluIssued.bits := alu.io.req.bits.uop.tag
  io.aguIssued := lsu.io.addressIssued
  io.dataReq <> lsu.io.request
  lsu.io.response <> io.dataResp

  val systemAtHead = rob.io.head.valid && rob.io.head.bits.fu === FuType.system &&
    !rob.io.head.bits.exception
  when(systemAtHead && !systemSent && !stopIssue && lsu.io.empty &&
    !alu.io.busy && !branch.io.busy && aluIssue.io.empty && branchIssue.io.empty) {
    systemResult := 0.U.asTypeOf(new ExeResult(p))
    systemResult.tag := rob.io.head.bits.tag
    systemResult.seq := rob.io.head.bits.seq
    systemResult.nextPc := rob.io.head.bits.pc + 4.U
    systemValid := true.B
    systemSent := true.B
  }

  val completion = Wire(Vec(4, Decoupled(new ExeResult(p))))
  completion(0) <> alu.io.resp
  completion(1) <> branch.io.resp
  completion(2) <> lsu.io.result
  completion(3).valid := systemValid
  completion(3).bits := systemResult
  when(completion(3).fire) { systemValid := false.B }
  val have = Wire(Vec(5, Bool()))
  val pick = Wire(Vec(5, UInt(2.W)))
  have(0) := false.B
  pick(0) := 0.U
  for (i <- 0 until 4) {
    val take = completion(i).valid && (!have(i) || completion(i).bits.seq < completion(pick(i)).bits.seq)
    have(i + 1) := have(i) || completion(i).valid
    pick(i + 1) := Mux(take, i.U, pick(i))
    completion(i).ready := have(4) && pick(4) === i.U
  }
  rob.io.wb.valid := have(4)
  rob.io.wb.bits := completion(pick(4)).bits
  val broadcast = Wire(Valid(new ExeResult(p)))
  broadcast.valid := rob.io.wbAccepted
  broadcast.bits := rob.io.wb.bits
  rename.io.wakeup := broadcast
  aluIssue.io.wakeup := broadcast
  branchIssue.io.wakeup := broadcast
  lsu.io.wakeup := broadcast
  io.writeback := broadcast

  rob.io.retire.ready := running && !draining && !rob.io.retire.bits.exception
  val commit = Wire(Valid(new CommitRecord(p)))
  commit.valid := rob.io.retire.fire
  commit.bits := rob.io.retire.bits
  rename.io.commit := commit
  io.retire := commit
  when(commit.valid && commit.bits.uop.barrier) {
    systemSent := false.B
    when(commit.bits.uop.op === UOpCode.HALT) {
      running := false.B
      done := true.B
    }.otherwise {
      frontend.io.redirect.valid := true.B
      frontend.io.redirect.bits := commit.bits.nextPc
    }
  }
  when(faultDetected) {
    draining := true.B
    fault := true.B
    faultPc := rob.io.retire.bits.uop.pc
    faultInst := rob.io.retire.bits.uop.inst
    faultCause := rob.io.retire.bits.cause
    faultAddr := rob.io.retire.bits.badAddr
  }
  when(finishFault) {
    running := false.B
    draining := false.B
  }
  when(flush) {
    systemValid := false.B
    systemSent := false.B
  }
  when(start) { running := true.B }
  when(io.ack && !running) {
    done := false.B
    fault := false.B
    faultPc := 0.U
    faultInst := 0.U
    faultCause := 0.U
    faultAddr := 0.U
  }
  io.status.busy := running
  io.status.done := done
  io.status.fault := fault
  io.status.faultPc := faultPc
  io.status.faultInst := faultInst
  io.status.faultCause := faultCause
  io.status.faultAddr := faultAddr
}
