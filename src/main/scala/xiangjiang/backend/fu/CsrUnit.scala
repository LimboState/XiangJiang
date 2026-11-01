package xiangjiang.backend.fu

import chisel3._
import chisel3.util._
import xiangjiang._

/** CSR / configuration unit.  Executes CSR instructions and vset* only when
  * the owning instruction is at the ROB head, reading committed CSR state.
  * Candidate updates ride the ROB entry and are published at commit in
  * program order; implicit fflags merges are applied the same way.
  *
  * CSR map: see docs/XJ-CSR-MAP.md.
  */
class CsrUnit(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new IssueEntry(p)))
    val resp = Decoupled(new ExeResult(p))
    val publish = Input(Valid(new CommitRecord(p)))
    val fflagsMerge = Input(Valid(UInt(5.W)))
    val flush = Input(Bool())
    val busy = Output(Bool())
    val fflags = Output(UInt(5.W))
    val frm = Output(UInt(3.W))
    val vstart = Output(UInt(32.W))
    val vxsat = Output(UInt(1.W))
    val vxrm = Output(UInt(2.W))
    val vl = Output(UInt(32.W))
    val vtype = Output(UInt(32.W))
  })

  // Committed CSR state.
  val fflagsReg = RegInit(0.U(5.W))
  val frmReg = RegInit(0.U(3.W))
  val vstartReg = RegInit(0.U(32.W))
  val vxsatReg = RegInit(0.U(1.W))
  val vxrmReg = RegInit(0.U(2.W))
  val vlReg = RegInit(0.U(32.W))
  val vtypeReg = RegInit(0.U(32.W))

  val occupied = RegInit(false.B)
  val completion = Reg(new ExeResult(p))
  // vset* with rd=x0 still updates vl; the ROB result pool cannot carry the
  // value in that case, so the execution stage latches it for publication.
  val pendingVl = Reg(UInt(32.W))
  io.req.ready := !io.flush && (!occupied || io.resp.ready)
  io.resp.valid := occupied && !io.flush
  io.resp.bits := completion
  io.busy := occupied

  io.fflags := fflagsReg
  io.frm := frmReg
  io.vstart := vstartReg
  io.vxsat := vxsatReg
  io.vxrm := vxrmReg
  io.vl := vlReg
  io.vtype := vtypeReg

  val addr = io.req.bits.uop.csrAddr
  val csrRead = WireDefault(0.U(32.W))
  val csrKnown = WireDefault(false.B)
  switch(addr) {
    is("h001".U) { csrRead := fflagsReg; csrKnown := true.B }
    is("h002".U) { csrRead := frmReg; csrKnown := true.B }
    is("h003".U) { csrRead := Cat(frmReg, fflagsReg); csrKnown := true.B }
    is("h008".U) { csrRead := vstartReg; csrKnown := true.B }
    is("h009".U) { csrRead := vxsatReg; csrKnown := true.B }
    is("h00a".U) { csrRead := vxrmReg; csrKnown := true.B }
    is("h00f".U) { csrRead := Cat(vxrmReg, vxsatReg); csrKnown := true.B }
    is("hc20".U) { csrRead := vlReg; csrKnown := true.B }
    is("hc21".U) { csrRead := vtypeReg; csrKnown := true.B }
    is("hc22".U) { csrRead := (p.vlen / 8).U; csrKnown := true.B }
    is("h7c0".U) { csrRead := 1.U; csrKnown := true.B } // architecture version
    is("h7c1".U) { csrRead := "h3".U; csrKnown := true.B } // capability bits
    is("h7c2".U) { csrRead := p.itcmBytes.U; csrKnown := true.B }
    is("h7c3".U) { csrRead := p.dtcmBytes.U; csrKnown := true.B }
  }
  val csrReadOnly = addr === "hc20".U || addr === "hc21".U || addr === "hc22".U ||
    addr === "h7c0".U || addr === "h7c1".U || addr === "h7c2".U || addr === "h7c3".U

  // vtype request decode and support check.  Baseline vector configuration
  // supports SEW 8/16/32 with EMUL >= 32, i.e. LMUL in {1, 1/2, 1/4} subject
  // to SEW/LMUL >= 32; anything else sets vill.
  val uop = io.req.bits.uop
  val isVset = uop.op === UOpCode.VSETVL || uop.op === UOpCode.VSETVLI ||
    uop.op === UOpCode.VSETIVLI
  val vtypeReq = Mux(uop.op === UOpCode.VSETVL,
    io.req.bits.src2.value(10, 0), uop.vtypei)
  val vsewReq = vtypeReq(5, 3)
  val vlmulReq = vtypeReq(2, 0)
  val sewSupported = vsewReq === 0.U || vsewReq === 1.U || vsewReq === 2.U
  val lmulSupported = vlmulReq === 0.U || vlmulReq === 7.U ||
    (vlmulReq === 6.U && vsewReq <= 1.U) || (vlmulReq === 5.U && vsewReq === 0.U)
  val vtypeReserved = vtypeReq(10, 8).orR
  val vill = vtypeReserved || !sewSupported || !lmulSupported
  val vlmax = MuxLookup(Cat(vsewReq, vlmulReq), 0.U(32.W))(Seq(
    Cat(0.U(3.W), 0.U(3.W)) -> 16.U, // SEW=8 LMUL=1
    Cat(0.U(3.W), 7.U(3.W)) -> 8.U, // SEW=8 LMUL=1/2
    Cat(0.U(3.W), 6.U(3.W)) -> 4.U, // SEW=8 LMUL=1/4
    Cat(1.U(3.W), 0.U(3.W)) -> 8.U, // SEW=16 LMUL=1
    Cat(1.U(3.W), 7.U(3.W)) -> 4.U, // SEW=16 LMUL=1/2
    Cat(2.U(3.W), 0.U(3.W)) -> 4.U)) // SEW=32 LMUL=1

  // CSR read-modify-write semantics.  Register forms CSRRW/CSRRS/CSRRC
  // (funct3 1..3); immediate forms 5..7.  CSRRS/CSRRC with rs1=x0 (or
  // uimm=0) only read.
  val csrF3 = uop.csrF3
  val csrWrite = uop.op === UOpCode.CSR &&
    (csrF3 === 1.U || csrF3 === 5.U ||
      ((csrF3 === 2.U || csrF3 === 3.U) && !uop.useImm && uop.rs1 =/= 0.U) ||
      ((csrF3 === 6.U || csrF3 === 7.U) && uop.useImm && uop.imm(4, 0) =/= 0.U))
  val src = Mux(uop.useImm && uop.op === UOpCode.CSR, uop.imm, io.req.bits.src1.value)
  val newValue = WireDefault(src)
  when(uop.op === UOpCode.CSR) {
    when(csrF3 === 2.U || csrF3 === 6.U) { newValue := csrRead | src }
    when(csrF3 === 3.U || csrF3 === 7.U) { newValue := csrRead & ~src }
  }
  val illegalCsr = uop.op === UOpCode.CSR && (!csrKnown || (csrWrite && csrReadOnly))
  val avl = Mux(uop.op === UOpCode.VSETIVLI, uop.imm, src)
  val vsetVill = isVset && vill
  val newVl = Mux(vsetVill, 0.U, Mux(avl < vlmax, avl, vlmax))
  val newVtype = Mux(vsetVill, "h80000000".U(32.W), Cat(0.U(21.W), vtypeReq))

  val result = WireDefault(0.U.asTypeOf(new ExeResult(p)))
  result.tag := uop.tag
  result.seq := uop.seq
  result.nextPc := uop.pc + 4.U
  when(isVset) {
    result.data := newVl
    result.csrWrEn := true.B
    result.csrAddr := "hc21".U
    result.csrData := newVtype
  }.elsewhen(uop.op === UOpCode.CSR) {
    result.data := csrRead
    result.exception := illegalCsr
    result.cause := FaultCode.illegalCsr
    when(!illegalCsr && csrWrite) {
      result.csrWrEn := true.B
      result.csrAddr := addr
      result.csrData := newValue
    }
  }

  when(io.flush) {
    occupied := false.B
  }.otherwise {
    when(io.resp.fire) { occupied := false.B }
    when(io.req.fire) {
      completion := result
      occupied := true.B
      when(isVset) { pendingVl := newVl }
    }
  }

  // Commit publication: explicit CSR writes, vset* configuration and implicit
  // fflags merges all apply in program order.
  when(io.publish.valid && !io.publish.bits.exception) {
    val u = io.publish.bits.uop
    when(u.op === UOpCode.CSR && io.publish.bits.csrWrEn) {
      switch(io.publish.bits.csrAddr) {
        is("h001".U) { fflagsReg := io.publish.bits.csrData(4, 0) }
        is("h002".U) {
          frmReg := Mux(io.publish.bits.csrData(2, 0) <= 4.U, io.publish.bits.csrData(2, 0), 0.U)
        }
        is("h003".U) {
          fflagsReg := io.publish.bits.csrData(4, 0)
          frmReg := Mux(io.publish.bits.csrData(7, 5) <= 4.U, io.publish.bits.csrData(7, 5), 0.U)
        }
        is("h008".U) { vstartReg := io.publish.bits.csrData }
        is("h009".U) { vxsatReg := io.publish.bits.csrData(0) }
        is("h00a".U) { vxrmReg := io.publish.bits.csrData(1, 0) }
        is("h00f".U) {
          vxsatReg := io.publish.bits.csrData(0)
          vxrmReg := io.publish.bits.csrData(2, 1)
        }
      }
    }
    when(u.op === UOpCode.VSETVL || u.op === UOpCode.VSETVLI || u.op === UOpCode.VSETIVLI) {
      vlReg := pendingVl
      vtypeReg := io.publish.bits.csrData
      vstartReg := 0.U
    }
  }
  when(io.fflagsMerge.valid) {
    fflagsReg := fflagsReg | io.fflagsMerge.bits
  }
}
