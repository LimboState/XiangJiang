package xiangjiang.backend.fu

import chisel3._
import chisel3.util._
import xiangjiang._

class ScalarUnitIO(p: XJParams) extends Bundle {
  val req = Flipped(Decoupled(new IssueEntry(p)))
  val resp = Decoupled(new ExeResult(p))
  val flush = Input(Bool())
  val busy = Output(Bool())
}

/** One registered completion, including instructions without a destination. */
abstract class ScalarFunctionalUnit(p: XJParams) extends Module {
  val io = IO(new ScalarUnitIO(p))
  protected val result = WireDefault(0.U.asTypeOf(new ExeResult(p)))
  protected val uop = io.req.bits.uop
  protected val a = io.req.bits.src1.value
  protected val b = io.req.bits.src2.value
  result.tag := uop.tag
  result.seq := uop.seq
  result.nextPc := uop.pc + 4.U

  private val occupied = RegInit(false.B)
  private val completion = Reg(new ExeResult(p))
  io.req.ready := !io.flush && (!occupied || io.resp.ready)
  io.resp.valid := occupied && !io.flush
  io.resp.bits := completion
  io.busy := occupied
  when(io.flush) {
    occupied := false.B
  }.otherwise {
    when(io.resp.fire) { occupied := false.B }
    when(io.req.fire) {
      completion := result
      occupied := true.B
    }
  }
}

class ALUUnit(p: XJParams) extends ScalarFunctionalUnit(p) {
  val rhs = Mux(uop.useImm, uop.imm, b)
  val shamt = rhs(4, 0)
  switch(uop.op) {
    is(UOpCode.ADD, UOpCode.ADDI) { result.data := a + rhs }
    is(UOpCode.SUB) { result.data := a - rhs }
    is(UOpCode.AND, UOpCode.ANDI) { result.data := a & rhs }
    is(UOpCode.OR, UOpCode.ORI) { result.data := a | rhs }
    is(UOpCode.XOR, UOpCode.XORI) { result.data := a ^ rhs }
    is(UOpCode.SLL, UOpCode.SLLI) { result.data := (a << shamt)(31, 0) }
    is(UOpCode.SRL, UOpCode.SRLI) { result.data := a >> shamt }
    is(UOpCode.SRA, UOpCode.SRAI) { result.data := (a.asSInt >> shamt).asUInt }
    is(UOpCode.SLT, UOpCode.SLTI) { result.data := (a.asSInt < rhs.asSInt).asUInt }
    is(UOpCode.SLTU, UOpCode.SLTIU) { result.data := (a < rhs).asUInt }
    is(UOpCode.LUI) { result.data := uop.imm }
    is(UOpCode.AUIPC) { result.data := uop.pc + uop.imm }
    // Zbb selection.
    is(UOpCode.CLZ) { result.data := PriorityEncoder(Reverse(a)) }
    is(UOpCode.CTZ) { result.data := PriorityEncoder(a) }
    is(UOpCode.CPOP) { result.data := PopCount(a) }
    is(UOpCode.ANDN) { result.data := a & ~b }
    is(UOpCode.ORN) { result.data := a | ~b }
    is(UOpCode.XNOR) { result.data := ~(a ^ b) }
    is(UOpCode.MIN) { result.data := Mux(a.asSInt < b.asSInt, a, b) }
    is(UOpCode.MINU) { result.data := Mux(a < b, a, b) }
    is(UOpCode.MAX) { result.data := Mux(a.asSInt > b.asSInt, a, b) }
    is(UOpCode.MAXU) { result.data := Mux(a > b, a, b) }
    is(UOpCode.SEXTB) { result.data := Cat(Fill(24, a(7)), a(7, 0)) }
    is(UOpCode.SEXTH) { result.data := Cat(Fill(16, a(15)), a(15, 0)) }
    is(UOpCode.ZEXTH) { result.data := Cat(0.U(16.W), a(15, 0)) }
    is(UOpCode.ROL) { result.data := (a << shamt)(31, 0) | (a >> (32.U - shamt)) }
    is(UOpCode.ROR, UOpCode.RORI) { result.data := (a >> shamt) | (a << (32.U - shamt))(31, 0) }
    is(UOpCode.REV8) { result.data := Cat(a(7, 0), a(15, 8), a(23, 16), a(31, 24)) }
    is(UOpCode.ORCB) {
      result.data := Cat(Fill(8, a(31, 24).orR), Fill(8, a(23, 16).orR),
        Fill(8, a(15, 8).orR), Fill(8, a(7, 0).orR))
    }
  }
}

/** Integer multiplier: MUL/MULH/MULHSU/MULHU with one registered completion. */
class MluUnit(p: XJParams) extends ScalarFunctionalUnit(p) {
  switch(uop.op) {
    is(UOpCode.MUL) { result.data := (a * b)(31, 0) }
    is(UOpCode.MULH) { result.data := (a.asSInt * b.asSInt)(63, 32).asUInt }
    is(UOpCode.MULHSU) { result.data := (a.asSInt * Cat(0.U(1.W), b).asSInt)(63, 32).asUInt }
    is(UOpCode.MULHU) { result.data := (a * b)(63, 32) }
  }
}

/** Integer divider: DIV/DIVU/REM/REMU with standard RISC-V boundary results. */
class DvuUnit(p: XJParams) extends ScalarFunctionalUnit(p) {
  val divZero = b === 0.U
  val signed = uop.op === UOpCode.DIV || uop.op === UOpCode.REM
  val overflow = signed && a === "h80000000".U && b === "hffffffff".U
  val den = Mux(divZero, 1.U, b)
  val sQuotient = (a.asSInt / den.asSInt).asUInt
  val uQuotient = a / den
  val sRemainder = (a.asSInt % den.asSInt).asUInt
  val uRemainder = a % den
  val isRem = uop.op === UOpCode.REM || uop.op === UOpCode.REMU
  result.data := Mux(isRem,
    Mux(divZero, a, Mux(overflow, 0.U, Mux(signed, sRemainder, uRemainder))),
    Mux(divZero, "hffffffff".U(32.W),
      Mux(overflow, a, Mux(signed, sQuotient, uQuotient))))
}

/** Scalar FP32 core: the F-extension selection.  Executes with the instruction
  * rounding mode or the dispatch-time frm snapshot; candidate exception flags
  * ride the completion record and merge into fflags at commit.
  */
class FloatCore(p: XJParams) extends ScalarFunctionalUnit(p) {
  private val fa = io.req.bits.fsrc1.value
  private val fb = io.req.bits.fsrc2.value
  private val fc = io.req.bits.fsrc3.value
  private val rm = Mux(uop.rm === 7.U, io.req.bits.frm, uop.rm)
  private val outResult = WireDefault(0.U(32.W))
  private val outFlags = WireDefault(0.U(5.W))
  private def apply(op: (UInt, UInt)): Unit = {
    outResult := op._1
    outFlags := op._2
  }
  private def negate(op: (UInt, UInt)): Unit = {
    val nanResult = op._1(30, 23).andR && op._1(22, 0).orR
    outResult := Mux(nanResult, "h7fc00000".U(32.W), op._1 ^ "h80000000".U(32.W))
    outFlags := op._2
  }
  switch(uop.op) {
    is(UOpCode.FADD) { apply(VectorFloat.f32Add(fa, fb, rm)) }
    is(UOpCode.FSUB) { apply(VectorFloat.f32Add(fa, Cat(!fb(31), fb(30, 0)), rm)) }
    is(UOpCode.FMUL) { apply(VectorFloat.f32Mul(fa, fb, rm)) }
    is(UOpCode.FDIV) { apply(VectorFloat.f32Div(fa, fb, rm)) }
    is(UOpCode.FSQRT) { apply(VectorFloat.f32Sqrt(fa, rm)) }
    is(UOpCode.FMADD) { apply(VectorFloat.f32Fma(fa, fb, fc, rm)) }
    is(UOpCode.FMSUB) { apply(VectorFloat.f32Fma(fa, fb, Cat(!fc(31), fc(30, 0)), rm)) }
    is(UOpCode.FNMSUB) { negate(VectorFloat.f32Fma(fa, fb, Cat(!fc(31), fc(30, 0)), rm)) }
    is(UOpCode.FNMADD) { negate(VectorFloat.f32Fma(fa, fb, fc, rm)) }
    is(UOpCode.FEQ) {
      val r = VectorFloat.f32Cmp(fa, fb, 0.U)
      outResult := r._1.asUInt
      outFlags := Cat(r._2, 0.U(4.W))
    }
    is(UOpCode.FLT) {
      val r = VectorFloat.f32Cmp(fa, fb, 1.U)
      outResult := r._1.asUInt
      outFlags := Cat(r._2, 0.U(4.W))
    }
    is(UOpCode.FLE) {
      val r = VectorFloat.f32Cmp(fa, fb, 2.U)
      outResult := r._1.asUInt
      outFlags := Cat(r._2, 0.U(4.W))
    }
    is(UOpCode.FCVTWS) { apply(VectorFloat.f32ToInt(fa, rm, false)) }
    is(UOpCode.FCVTWUS) { apply(VectorFloat.f32ToInt(fa, rm, true)) }
    is(UOpCode.FCVTSW) { apply(VectorFloat.intToF32(fa, rm, false)) }
    is(UOpCode.FCVTSWU) { apply(VectorFloat.intToF32(fa, rm, true)) }
    is(UOpCode.FMVXW) {
      outResult := fa
      outFlags := 0.U
    }
    is(UOpCode.FMVWX) {
      outResult := a
      outFlags := 0.U
    }
  }
  result.data := outResult
  result.fflags := outFlags
}

/** Issued only at the ROB head; checks the selected PC, never an unused target. */
class BranchUnit(p: XJParams) extends ScalarFunctionalUnit(p) {
  val taken = WireDefault(false.B)
  switch(uop.op) {
    is(UOpCode.BEQ) { taken := a === b }
    is(UOpCode.BNE) { taken := a =/= b }
    is(UOpCode.BLT) { taken := a.asSInt < b.asSInt }
    is(UOpCode.BGE) { taken := a.asSInt >= b.asSInt }
    is(UOpCode.BLTU) { taken := a < b }
    is(UOpCode.BGEU) { taken := a >= b }
    is(UOpCode.JAL, UOpCode.JALR) { taken := true.B }
  }
  val indirect = (a + uop.imm) & "hfffffffe".U(32.W)
  val target = Mux(uop.op === UOpCode.JALR, indirect, uop.pc + uop.imm)
  val nextPc = Mux(taken, target, uop.pc + 4.U)
  val misaligned = nextPc(1, 0) =/= 0.U
  val outside = nextPc >= p.itcmBytes.U
  val fault = misaligned || outside
  val link = uop.op === UOpCode.JAL || uop.op === UOpCode.JALR
  result.nextPc := nextPc
  result.data := Mux(link && !fault, uop.pc + 4.U, 0.U)
  result.exception := fault
  result.cause := Mux(misaligned, FaultCode.fetchAlign, Mux(outside, FaultCode.fetchAccess, FaultCode.none))
  result.badAddr := Mux(fault, nextPc, 0.U)
}
