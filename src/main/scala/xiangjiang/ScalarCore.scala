package xiangjiang

import chisel3._
import chisel3.util._
import xiangjiang.backend.decode.DecodeUnit
import xiangjiang.backend.fu.{ALUUnit, BranchUnit, CsrUnit, DvuUnit, FloatCore, MatrixUnit, MluUnit, VectorUnit}
import xiangjiang.backend.issue.IssueUnit
import xiangjiang.backend.rename.RenameStage
import xiangjiang.backend.rob.Rob
import xiangjiang.frontend.Frontend
import xiangjiang.mem.LSU

/** Single-dispatch scalar/vector core with value renaming and ordered commit.
  * Dispatch captures the committed vector configuration (vl/vtype/vstart/vxrm)
  * and the committed frm snapshot; context-changing CSR and vset* operations
  * are dispatch barriers, so younger consumers only observe committed state.
  */
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
    val dmaCommand = Decoupled(new DmaCommand)
    val dmaCompletion = Flipped(Decoupled(new DmaCompletion))
    val dmaBusy = Input(Bool())
    val status = Output(new CoreStatus)
    val debugRegs = Output(Vec(32, UInt(32.W)))
    val debugFRegs = Output(Vec(32, UInt(32.W)))
    val debugVRegs = Output(Vec(32, UInt(p.vlen.W)))
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
  val mluIssue = Module(new IssueUnit(p))
  val dvuIssue = Module(new IssueUnit(p))
  val floatIssue = Module(new IssueUnit(p))
  val branchIssue = Module(new IssueUnit(p, headOnly = true))
  val vectorIssue = Module(new IssueUnit(p, vectorEnabled = true))
  val csrIssue = Module(new IssueUnit(p, headOnly = true))
  val alu = Module(new ALUUnit(p))
  val mlu = Module(new MluUnit(p))
  val dvu = Module(new DvuUnit(p))
  val floatCore = Module(new FloatCore(p))
  val branch = Module(new BranchUnit(p))
  val vector = Module(new VectorUnit(p))
  val matrix = Module(new MatrixUnit(p))
  val csr = Module(new CsrUnit(p))
  val lsu = Module(new LSU(p))

  val running = RegInit(false.B)
  val draining = RegInit(false.B)
  val done = RegInit(false.B)
  val fault = RegInit(false.B)
  val faultPc = RegInit(0.U(32.W))
  val faultInst = RegInit(0.U(32.W))
  val faultCause = RegInit(0.U(5.W))
  val faultAddr = RegInit(0.U(32.W))
  val systemValid = RegInit(false.B)
  val systemSent = RegInit(false.B)
  val systemResult = Reg(new ExeResult(p))

  val start = io.start && !running && !done && !fault
  val faultDetected = running && !draining && rob.io.retire.valid && rob.io.retire.bits.exception
  val stopIssue = !running || draining || faultDetected
  val quiescent = !alu.io.busy && !mlu.io.busy && !dvu.io.busy && !floatCore.io.busy &&
    !branch.io.busy && !vector.io.busy && !matrix.io.busy && !csr.io.busy &&
    lsu.io.quiescent && !systemValid
  val finishFault = draining && quiescent
  val flush = start || finishFault
  // Fault cleanup discards unissued reservations after completion sources drain.
  rob.io.quiescent := quiescent
  rob.io.flush := flush
  rename.io.flush := flush
  for (issue <- Seq(aluIssue, mluIssue, dvuIssue, floatIssue, branchIssue, vectorIssue, csrIssue)) {
    issue.io.flush := flush
  }
  alu.io.flush := flush
  mlu.io.flush := flush
  dvu.io.flush := flush
  floatCore.io.flush := flush
  branch.io.flush := flush
  vector.io.flush := flush
  matrix.io.flush := flush
  csr.io.flush := flush
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

  val decoded = WireDefault(decoder.io.out)
  val uop = WireDefault(decoded)
  // Dispatch-time configuration snapshots from committed CSR state.
  uop.vl := csr.io.vl
  uop.vsew := csr.io.vtype(5, 3)
  uop.vlmul := csr.io.vtype(2, 0)
  uop.vstart := csr.io.vstart
  uop.vxrm := csr.io.vxrm
  // Runtime legality checks that decoding cannot see.
  val dispatchFault = WireDefault(false.B)
  val dispatchCause = WireDefault(FaultCode.none)
  val vecFp32 = uop.vecOp >= VecOp.vfadd32.U && uop.vecOp <= VecOp.vfredmax32.U
  val vecMemOk = MuxLookup(uop.vecOp, true.B)(Seq(
    VecOp.vle8.U -> (uop.vsew === 0.U), VecOp.vse8.U -> (uop.vsew === 0.U),
    VecOp.vle16.U -> (uop.vsew === 1.U), VecOp.vse16.U -> (uop.vsew === 1.U),
    VecOp.vle32.U -> (uop.vsew === 2.U), VecOp.vse32.U -> (uop.vsew === 2.U)))
  when(decoded.op === UOpCode.VECTOR && !decoded.exception) {
    when(csr.io.vtype(31)) {
      dispatchFault := true.B
      dispatchCause := FaultCode.vtypeVill
    }.elsewhen(csr.io.vstart =/= 0.U) {
      dispatchFault := true.B
      dispatchCause := FaultCode.vstartActive
    }.elsewhen(vecFp32 && uop.vsew =/= 2.U) {
      dispatchFault := true.B
      dispatchCause := FaultCode.vtypeVill
    }.elsewhen(!vecMemOk) {
      dispatchFault := true.B
      dispatchCause := FaultCode.vtypeVill
    }
  }
  when(decoded.fu === FuType.fpu && decoded.rm === 7.U && csr.io.frm > 4.U) {
    dispatchFault := true.B
    dispatchCause := FaultCode.frmIllegal
  }
  when(dispatchFault) {
    uop.exception := true.B
    uop.cause := dispatchCause
    uop.badAddr := uop.inst
    uop.usesRs1 := false.B
    uop.usesRs2 := false.B
    uop.usesF1 := false.B
    uop.usesF2 := false.B
    uop.usesF3 := false.B
    uop.writesRd := false.B
    uop.writesF := false.B
    uop.writesV := false.B
    uop.usesV1 := false.B
    uop.usesV2 := false.B
    uop.usesVOld := false.B
    uop.usesVMask := false.B
    uop.barrier := true.B
  }
  uop.tag := rob.io.allocTag
  uop.seq := rob.io.allocSeq
  rename.io.uop := uop
  rename.io.frm := csr.io.frm
  rob.io.query := rename.io.lookupTags
  rename.io.lookupValues := rob.io.values
  rob.io.fQuery := rename.io.fLookupTags
  rename.io.fLookupValues := rob.io.fValues
  io.debugRegs := rename.io.debugRegs
  io.debugFRegs := rename.io.debugFRegs
  rob.io.vectorQuery := rename.io.vectorLookupTags
  rename.io.vectorLookupValues := rob.io.vectorValues
  io.debugVRegs := rename.io.debugVRegs

  val isAlu = uop.fu === FuType.alu && !uop.exception
  val isMlu = uop.fu === FuType.mlu && !uop.exception
  val isDvu = uop.fu === FuType.dvu && !uop.exception
  val isFpu = uop.fu === FuType.fpu && !uop.exception
  val isBranch = uop.fu === FuType.bru && !uop.exception
  val isVector = uop.fu === FuType.vector && !uop.exception
  val isCsrOp = uop.fu === FuType.csr && !uop.exception
  val isMatrixTask = (uop.fu === FuType.mem || uop.fu === FuType.matrix) && !uop.exception
  val targetReady = (!isAlu || aluIssue.io.dispatch.ready) &&
    (!isMlu || mluIssue.io.dispatch.ready) && (!isDvu || dvuIssue.io.dispatch.ready) &&
    (!isFpu || floatIssue.io.dispatch.ready) && (!isBranch || branchIssue.io.dispatch.ready) &&
    (!isMatrixTask || lsu.io.allocate.ready) && (!isVector || vectorIssue.io.dispatch.ready) &&
    (!isCsrOp || csrIssue.io.dispatch.ready)
  val dispatch = frontend.io.fetch.valid && !stopIssue && !flush && rob.io.allocate.ready && targetReady
  frontend.io.fetch.ready := !stopIssue && !flush && rob.io.allocate.ready && targetReady
  // Each participant observes valid only when the entire allocation can succeed.
  rob.io.allocate.valid := dispatch
  rob.io.allocate.bits := uop
  rename.io.dispatch := dispatch
  aluIssue.io.dispatch.valid := dispatch && isAlu
  aluIssue.io.dispatch.bits := rename.io.renamed
  mluIssue.io.dispatch.valid := dispatch && isMlu
  mluIssue.io.dispatch.bits := rename.io.renamed
  dvuIssue.io.dispatch.valid := dispatch && isDvu
  dvuIssue.io.dispatch.bits := rename.io.renamed
  floatIssue.io.dispatch.valid := dispatch && isFpu
  floatIssue.io.dispatch.bits := rename.io.renamed
  branchIssue.io.dispatch.valid := dispatch && isBranch
  branchIssue.io.dispatch.bits := rename.io.renamed
  vectorIssue.io.dispatch.valid := dispatch && isVector
  vectorIssue.io.dispatch.bits := rename.io.renamed
  csrIssue.io.dispatch.valid := dispatch && isCsrOp
  csrIssue.io.dispatch.bits := rename.io.renamed
  lsu.io.allocate.valid := dispatch && isMatrixTask
  lsu.io.allocate.bits := rename.io.renamed

  for (issue <- Seq(aluIssue, mluIssue, dvuIssue, floatIssue, branchIssue, vectorIssue, csrIssue)) {
    issue.io.head.valid := rob.io.head.valid
    issue.io.head.bits := rob.io.head.bits.tag
  }
  lsu.io.head.valid := rob.io.head.valid
  lsu.io.head.bits := rob.io.head.bits.tag
  alu.io.req.valid := aluIssue.io.issue.valid && !stopIssue
  alu.io.req.bits := aluIssue.io.issue.bits
  aluIssue.io.issue.ready := alu.io.req.ready && !stopIssue
  mlu.io.req.valid := mluIssue.io.issue.valid && !stopIssue
  mlu.io.req.bits := mluIssue.io.issue.bits
  mluIssue.io.issue.ready := mlu.io.req.ready && !stopIssue
  dvu.io.req.valid := dvuIssue.io.issue.valid && !stopIssue
  dvu.io.req.bits := dvuIssue.io.issue.bits
  dvuIssue.io.issue.ready := dvu.io.req.ready && !stopIssue
  floatCore.io.req.valid := floatIssue.io.issue.valid && !stopIssue
  floatCore.io.req.bits := floatIssue.io.issue.bits
  floatIssue.io.issue.ready := floatCore.io.req.ready && !stopIssue
  branch.io.req.valid := branchIssue.io.issue.valid && !stopIssue
  branch.io.req.bits := branchIssue.io.issue.bits
  branchIssue.io.issue.ready := branch.io.req.ready && !stopIssue
  vector.io.req.valid := vectorIssue.io.issue.valid && !stopIssue
  vector.io.req.bits := vectorIssue.io.issue.bits
  vectorIssue.io.issue.ready := vector.io.req.ready && !stopIssue
  csr.io.req.valid := csrIssue.io.issue.valid && !stopIssue
  csr.io.req.bits := csrIssue.io.issue.bits
  csrIssue.io.issue.ready := csr.io.req.ready && !stopIssue
  io.aluIssued.valid := alu.io.req.fire
  io.aluIssued.bits := alu.io.req.bits.uop.tag
  io.aguIssued := lsu.io.addressIssued
  io.dataReq <> lsu.io.request
  lsu.io.response <> io.dataResp
  lsu.io.matrixTask <> matrix.io.task
  lsu.io.matrixDone := matrix.io.done
  io.dmaCommand <> lsu.io.dmaCommand
  lsu.io.dmaCompletion <> io.dmaCompletion
  lsu.io.dmaBusy := io.dmaBusy
  io.matrixReq <> matrix.io.memReq
  matrix.io.memResp <> io.matrixResp

  val systemAtHead = rob.io.head.valid && rob.io.head.bits.fu === FuType.system &&
    !rob.io.head.bits.exception
  when(systemAtHead && !systemSent && !stopIssue && lsu.io.empty &&
    !alu.io.busy && !mlu.io.busy && !dvu.io.busy && !floatCore.io.busy &&
    !branch.io.busy && !vector.io.busy && !matrix.io.busy && !csr.io.busy &&
    aluIssue.io.empty && mluIssue.io.empty && dvuIssue.io.empty && floatIssue.io.empty &&
    branchIssue.io.empty && vectorIssue.io.empty && csrIssue.io.empty) {
    systemResult := 0.U.asTypeOf(new ExeResult(p))
    systemResult.tag := rob.io.head.bits.tag
    systemResult.seq := rob.io.head.bits.seq
    systemResult.nextPc := rob.io.head.bits.pc + 4.U
    val systemFault = rob.io.head.bits.op === UOpCode.EBREAK ||
      rob.io.head.bits.op === UOpCode.ECALL
    systemResult.exception := systemFault
    systemResult.cause := Mux(rob.io.head.bits.op === UOpCode.EBREAK,
      FaultCode.ebreak, Mux(rob.io.head.bits.op === UOpCode.ECALL,
        FaultCode.ecall, FaultCode.none))
    systemResult.badAddr := Mux(systemFault, rob.io.head.bits.inst, 0.U)
    systemValid := true.B
    systemSent := true.B
  }

  val completion = Wire(Vec(9, Decoupled(new ExeResult(p))))
  completion(0) <> alu.io.resp
  completion(1) <> mlu.io.resp
  completion(2) <> dvu.io.resp
  completion(3) <> floatCore.io.resp
  completion(4) <> branch.io.resp
  completion(5) <> lsu.io.result
  completion(6) <> vector.io.resp
  completion(7) <> csr.io.resp
  completion(8).valid := systemValid
  completion(8).bits := systemResult
  when(completion(8).fire) { systemValid := false.B }
  val have = Wire(Vec(10, Bool()))
  val pick = Wire(Vec(10, UInt(4.W)))
  have(0) := false.B
  pick(0) := 0.U
  for (i <- 0 until 9) {
    val take = completion(i).valid && (!have(i) || completion(i).bits.seq < completion(pick(i)).bits.seq)
    have(i + 1) := have(i) || completion(i).valid
    pick(i + 1) := Mux(take, i.U, pick(i))
    completion(i).ready := have(9) && pick(9) === i.U
  }
  rob.io.wb.valid := have(9)
  rob.io.wb.bits := completion(pick(9)).bits
  val broadcast = Wire(Valid(new ExeResult(p)))
  broadcast.valid := rob.io.wbAccepted
  broadcast.bits := rob.io.wb.bits
  rename.io.wakeup := broadcast
  for (issue <- Seq(aluIssue, mluIssue, dvuIssue, floatIssue, branchIssue, vectorIssue, csrIssue)) {
    issue.io.wakeup := broadcast
  }
  lsu.io.wakeup := broadcast
  io.writeback := broadcast

  rob.io.retire.ready := running && !draining && !rob.io.retire.bits.exception
  val commit = Wire(Valid(new CommitRecord(p)))
  commit.valid := rob.io.retire.fire
  commit.bits := rob.io.retire.bits
  rename.io.commit := commit
  csr.io.publish := commit
  csr.io.fflagsMerge.valid := commit.valid && !commit.bits.exception &&
    commit.bits.uop.fu === FuType.fpu
  csr.io.fflagsMerge.bits := commit.bits.fflags
  io.retire := commit
  val matrixPublishing = commit.valid && !commit.bits.exception &&
    commit.bits.uop.op === UOpCode.MATRIX &&
    (commit.bits.uop.matOp === MatOp.msetmode || commit.bits.uop.matOp === MatOp.mload ||
      commit.bits.uop.matOp === MatOp.mmul || commit.bits.uop.matOp === MatOp.mmacc ||
      commit.bits.uop.matOp === MatOp.mew || commit.bits.uop.matOp === MatOp.mtrans)
  matrix.io.publish.valid := matrixPublishing
  matrix.io.publish.bits.tag := commit.bits.uop.tag
  matrix.io.publish.bits.op := commit.bits.uop.matOp
  matrix.io.publish.bits.unit := commit.bits.uop.matUnit
  matrix.io.publish.bits.buffer := commit.bits.uop.matBuffer
  matrix.io.publish.bits.mode := commit.bits.uop.matMode
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
