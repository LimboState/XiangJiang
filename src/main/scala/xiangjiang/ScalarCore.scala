package xiangjiang

import chisel3._
import chisel3.util._
import xiangjiang.backend.decode.DecodeUnit
import xiangjiang.backend.fu.{ALUUnit, BranchUnit, MatrixUnit, VectorUnit}
import xiangjiang.backend.issue.IssueUnit
import xiangjiang.backend.rename.RenameStage
import xiangjiang.backend.rob.Rob
import xiangjiang.frontend.Frontend
import xiangjiang.mem.{LSU, DmaEngine}
import device.Axi4Slave

/** Single-dispatch scalar/vector core with value renaming and ordered commit. */
class ScalarCore(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val ack = Input(Bool())
    val fetchReq = Decoupled(UInt(32.W))
    val fetchResp = Input(Valid(new FetchPacket))
    val dataReq = Decoupled(new WordReq)
    val dataResp = Flipped(Decoupled(new WordResp))
    val matrixReq = Decoupled(new MatrixBeatReq)
    val matrixResp = Flipped(Decoupled(new MatrixBeatResp))
    val external = Flipped(new Axi4Slave(p.axiIdBits))
    val status = Output(new CoreStatus)
    val debugRegs = Output(Vec(32, UInt(32.W)))
    val debugVRegs = Output(Vec(32, UInt(p.vectorBits.W)))
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
  val vectorIssues = Seq.fill(5)(Module(new IssueUnit(p, vectorEnabled = true)))
  val matrixIssue = Module(new IssueUnit(p, headOnly = true))
  val dmaIssue = Module(new IssueUnit(p, headOnly = true))
  val dma = Module(new DmaEngine(p))
  io.external <> dma.io.axi
  dma.io.ack := io.ack
  val alu = Module(new ALUUnit(p))
  val branch = Module(new BranchUnit(p))
  val vectors = (0 until 5).map(g => Module(new VectorUnit(p, g)))
  val vectorBusy = vectors.map(_.io.busy).reduce(_ || _)
  val vectorEmpty = vectorIssues.map(_.io.empty).reduce(_ && _)
  val matrix = Module(new MatrixUnit(p))
  val lsu = Module(new LSU(p))

  val running = RegInit(false.B)
  val draining = RegInit(false.B)
  val done = RegInit(false.B)
  val fault = RegInit(false.B)
  val faultPc = RegInit(0.U(32.W))
  val faultInst = RegInit(0.U(32.W))
  val faultCause = RegInit(0.U(5.W))
  val faultDetail = RegInit(0.U(32.W))
  val faultAddr = RegInit(0.U(32.W))
  val systemValid = RegInit(false.B)
  val systemSent = RegInit(false.B)
  val systemResult = Reg(new ExeResult(p))
  val matrixControlActive = RegInit(false.B)
  val matrixControlValid = RegInit(false.B)
  val matrixControlResult = Reg(new ExeResult(p))

  val start = io.start && !running && !done && !fault && !dma.io.status.poisoned
  val faultDetected = running && !draining && rob.io.retire.valid && rob.io.retire.bits.exception
  val stopIssue = !running || draining || faultDetected || dma.io.status.poisoned
  val quiescent = !alu.io.busy && !branch.io.busy && !vectorBusy && !matrix.io.busy &&
    lsu.io.quiescent && !dma.io.busy && !systemValid && !matrixControlActive && !matrixControlValid
  val finishFault = draining && quiescent
  val flush = start || finishFault
  // Fault cleanup discards unissued reservations after completion sources drain.
  rob.io.quiescent := quiescent
  rob.io.flush := flush
  rename.io.flush := flush
  aluIssue.io.flush := flush
  branchIssue.io.flush := flush
  vectorIssues.foreach(_.io.flush := flush)
  matrixIssue.io.flush := flush
  dmaIssue.io.flush := flush
  alu.io.flush := flush
  branch.io.flush := flush
  vectors.foreach(_.io.flush := flush)
  matrix.io.flush := flush
  lsu.io.flush := flush
  lsu.io.stop := stopIssue

  io.fetchReq <> frontend.io.request
  frontend.io.response := io.fetchResp
  frontend.io.start := start
  frontend.io.stopRun := !running && !start || draining || faultDetected || dma.io.status.poisoned
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
  rob.io.vectorQuery := rename.io.vectorLookupTags
  rename.io.vectorLookupValues := rob.io.vectorValues
  io.debugVRegs := rename.io.debugVRegs

  val isAlu = uop.fu === FuType.alu && !uop.exception
  val isBranch = uop.fu === FuType.bru && !uop.exception
  val isVector = uop.fu === FuType.vector && !uop.exception
  val vop = uop.vecOp
  val vectorGroup = Mux(vop === VecOp.vmul.U || vop === (VecOp.vmul + 1).U, 1.U,
    Mux(vop === VecOp.vfdiv.U || vop === (VecOp.vfdiv + 1).U, 3.U,
      Mux((vop >= VecOp.vfadd.U && vop <= (VecOp.vfmin + 1).U) ||
        (vop >= VecOp.vmfeq.U && vop <= (VecOp.vmfle + 1).U) || vop === VecOp.vunary.U, 2.U,
        Mux(vop >= VecOp.vslideup.U, 4.U, 0.U))))
  val isMemory = uop.fu === FuType.mem && !uop.exception
  val isMatrixControl = uop.fu === FuType.matrix && !uop.exception
  val isDmaConfig = uop.fu === FuType.dmaConfig && !uop.exception
  val targetReady = (!isAlu || aluIssue.io.dispatch.ready) &&
    (!isBranch || branchIssue.io.dispatch.ready) && (!isMemory || lsu.io.allocate.ready) &&
    (!isMatrixControl || matrixIssue.io.dispatch.ready) &&
    (!isDmaConfig || dmaIssue.io.dispatch.ready) &&
    (!isVector || VecInit(vectorIssues.map(_.io.dispatch.ready))(vectorGroup))
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
  for ((issue, g) <- vectorIssues.zipWithIndex) {
    issue.io.dispatch.valid := dispatch && isVector && vectorGroup === g.U
    issue.io.dispatch.bits := rename.io.renamed
  }
  matrixIssue.io.dispatch.valid := dispatch && isMatrixControl
  matrixIssue.io.dispatch.bits := rename.io.renamed
  dmaIssue.io.dispatch.valid := dispatch && isDmaConfig
  dmaIssue.io.dispatch.bits := rename.io.renamed
  lsu.io.allocate.valid := dispatch && isMemory
  lsu.io.allocate.bits := rename.io.renamed

  for (issue <- Seq(aluIssue, branchIssue, matrixIssue, dmaIssue) ++ vectorIssues) {
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
  for ((unit, issue) <- vectors.zip(vectorIssues)) {
    unit.io.req.valid := issue.io.issue.valid && !stopIssue
    unit.io.req.bits := issue.io.issue.bits
    issue.io.issue.ready := unit.io.req.ready && !stopIssue
  }
  io.aluIssued.valid := alu.io.req.fire
  io.aluIssued.bits := alu.io.req.bits.uop.tag
  io.aguIssued := lsu.io.addressIssued
  // Unified MemQ guarantees that DMA and scalar/vector accesses cannot overlap.
  val dmaLocal = dma.io.status.active
  io.dataReq.valid := Mux(dmaLocal, dma.io.localReq.valid, lsu.io.request.valid)
  io.dataReq.bits := Mux(dmaLocal, dma.io.localReq.bits, lsu.io.request.bits)
  dma.io.localReq.ready := io.dataReq.ready && dmaLocal
  lsu.io.request.ready := io.dataReq.ready && !dmaLocal
  dma.io.localResp.valid := io.dataResp.valid && dmaLocal
  dma.io.localResp.bits := io.dataResp.bits
  lsu.io.response.valid := io.dataResp.valid && !dmaLocal
  lsu.io.response.bits := io.dataResp.bits
  io.dataResp.ready := Mux(dmaLocal, dma.io.localResp.ready, lsu.io.response.ready)
  val dmaConfigRequest = dmaIssue.io.issue.valid && !stopIssue
  dma.io.command.valid := lsu.io.dmaTask.valid || dmaConfigRequest
  dma.io.command.bits := Mux(lsu.io.dmaTask.valid, lsu.io.dmaTask.bits, dmaIssue.io.issue.bits)
  lsu.io.dmaTask.ready := dma.io.command.ready
  dmaIssue.io.issue.ready := dma.io.command.ready && !lsu.io.dmaTask.valid && !stopIssue
  val dmaConfigActive = RegInit(false.B)
  when(dma.io.command.fire) {
    dmaConfigActive := dma.io.command.bits.uop.fu === FuType.dmaConfig
  }
  lsu.io.dmaResult.valid := dma.io.result.valid && !dmaConfigActive
  lsu.io.dmaResult.bits := dma.io.result.bits
  // Pure compute/MWAIT have a control RS, never occupy the memory queue.
  // Both paths start only at ROB head, so at most one can be authorized.
  val controlTask = matrixIssue.io.issue
  val startControl = controlTask.valid && !stopIssue && !matrixControlActive && !matrixControlValid
  matrix.io.task.valid := lsu.io.matrixTask.valid || startControl
  matrix.io.task.bits := lsu.io.matrixTask.bits
  when(!lsu.io.matrixTask.valid) {
    matrix.io.task.bits.tag := controlTask.bits.uop.tag
    matrix.io.task.bits.op := controlTask.bits.uop.matOp
    matrix.io.task.bits.unit := controlTask.bits.uop.matUnit
    matrix.io.task.bits.buffer := 0.U
    matrix.io.task.bits.addr := 0.U
  }
  lsu.io.matrixTask.ready := matrix.io.task.ready
  controlTask.ready := matrix.io.task.ready && !lsu.io.matrixTask.valid && startControl
  when(controlTask.fire) {
    matrixControlActive := true.B
    matrixControlResult := 0.U.asTypeOf(new ExeResult(p))
    matrixControlResult.tag := controlTask.bits.uop.tag
    matrixControlResult.seq := controlTask.bits.uop.seq
    matrixControlResult.nextPc := controlTask.bits.uop.pc + 4.U
  }
  when(matrix.io.done && matrixControlActive) {
    matrixControlActive := false.B
    matrixControlValid := true.B
    matrixControlResult.exception := matrix.io.stateFault
    matrixControlResult.cause := Mux(matrix.io.stateFault, FaultCode.matrixState, FaultCode.none)
    matrixControlResult.detail := Mux(matrix.io.stateFault,
      "h02010000".U | (rob.io.head.bits.matUnit << 8), 0.U)
  }
  lsu.io.matrixDone := matrix.io.done && !matrixControlActive
  lsu.io.matrixStateFault := matrix.io.stateFault
  lsu.io.matrixAccessFault := matrix.io.accessFault
  io.matrixReq <> matrix.io.memReq
  matrix.io.memResp <> io.matrixResp

  val systemAtHead = rob.io.head.valid && rob.io.head.bits.fu === FuType.system &&
    !rob.io.head.bits.exception
  when(systemAtHead && !systemSent && !stopIssue && lsu.io.empty &&
    !alu.io.busy && !branch.io.busy && !vectorBusy && !matrix.io.busy &&
    aluIssue.io.empty && branchIssue.io.empty && vectorEmpty && matrixIssue.io.empty && dmaIssue.io.empty && !dma.io.busy) {
    systemResult := 0.U.asTypeOf(new ExeResult(p))
    systemResult.tag := rob.io.head.bits.tag
    systemResult.seq := rob.io.head.bits.seq
    systemResult.nextPc := rob.io.head.bits.pc + 4.U
    systemValid := true.B
    systemSent := true.B
  }

  val completion = Wire(Vec(11, Decoupled(new ExeResult(p))))
  completion(0) <> alu.io.resp
  completion(1) <> branch.io.resp
  completion(2) <> lsu.io.result
  completion(3) <> vectors(0).io.resp
  for (g <- 1 until 5) completion(6 + g) <> vectors(g).io.resp
  completion(4).valid := systemValid
  completion(4).bits := systemResult
  when(completion(4).fire) { systemValid := false.B }
  completion(5).valid := matrixControlValid
  completion(5).bits := matrixControlResult
  when(completion(5).fire) { matrixControlValid := false.B }
  completion(6).valid := dma.io.result.valid && dmaConfigActive
  completion(6).bits := dma.io.result.bits
  dma.io.result.ready := Mux(dmaConfigActive, completion(6).ready, lsu.io.dmaResult.ready)
  val have = Wire(Vec(12, Bool()))
  val pick = Wire(Vec(12, UInt(4.W)))
  have(0) := false.B
  pick(0) := 0.U
  for (i <- 0 until 11) {
    val take = completion(i).valid && (!have(i) || completion(i).bits.seq < completion(pick(i)).bits.seq)
    have(i + 1) := have(i) || completion(i).valid
    pick(i + 1) := Mux(take, i.U, pick(i))
    completion(i).ready := have(11) && pick(11) === i.U
  }
  rob.io.wb.valid := have(11)
  rob.io.wb.bits := completion(pick(11)).bits
  val broadcast = Wire(Valid(new ExeResult(p)))
  broadcast.valid := rob.io.wbAccepted
  broadcast.bits := rob.io.wb.bits
  rename.io.wakeup := broadcast
  aluIssue.io.wakeup := broadcast
  branchIssue.io.wakeup := broadcast
  vectorIssues.foreach(_.io.wakeup := broadcast)
  matrixIssue.io.wakeup := broadcast
  dmaIssue.io.wakeup := broadcast
  lsu.io.wakeup := broadcast
  io.writeback := broadcast

  rob.io.retire.ready := running && !draining && !rob.io.retire.bits.exception && !dma.io.status.poisoned
  val commit = Wire(Valid(new CommitRecord(p)))
  commit.valid := rob.io.retire.fire
  commit.bits := rob.io.retire.bits
  rename.io.commit := commit
  io.retire := commit
  dma.io.commit.valid := commit.valid && commit.bits.uop.fu === FuType.dmaConfig
  dma.io.commit.bits := commit.bits.uop.tag
  matrix.io.commit.valid := commit.valid && commit.bits.uop.op === UOpCode.MATRIX
  matrix.io.commit.bits := commit.bits.uop.tag
  matrix.io.config.valid := commit.valid && commit.bits.uop.op === UOpCode.MATRIX &&
    commit.bits.uop.matOp === MatOp.msetmode
  matrix.io.config.bits.unit := commit.bits.uop.matUnit
  matrix.io.config.bits.mode := commit.bits.uop.matMode
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
    faultPc := rob.io.retire.bits.uop.pc
    faultInst := rob.io.retire.bits.uop.inst
    faultCause := rob.io.retire.bits.cause
    faultAddr := rob.io.retire.bits.badAddr
    faultDetail := rob.io.retire.bits.detail
  }
  when(finishFault) {
    fault := true.B
    running := false.B
    draining := false.B
  }
  when(flush) {
    matrixControlActive := false.B
    matrixControlValid := false.B
    systemValid := false.B
    systemSent := false.B
  }
  when(start) { running := true.B }
  when(io.ack && !running && !dma.io.status.poisoned) {
    done := false.B
    fault := false.B
    faultPc := 0.U
    faultInst := 0.U
    faultCause := 0.U
    faultAddr := 0.U
    faultDetail := 0.U
  }
  when(dma.io.status.poisoned) {
    done := false.B
    fault := false.B
    faultCause := FaultCode.dmaProtocol
    faultAddr := dma.io.fault.badAddr
    faultDetail := dma.io.fault.detail
    when(rob.io.head.valid) { faultPc := rob.io.head.bits.pc; faultInst := rob.io.head.bits.inst }
  }
  io.status.busy := running || dma.io.status.poisoned
  io.status.done := done
  io.status.fault := fault
  io.status.faultPc := faultPc
  io.status.faultInst := faultInst
  io.status.faultCause := faultCause
  io.status.faultAddr := faultAddr
  io.status.faultDetail := faultDetail
  io.status.dma := dma.io.status
}
