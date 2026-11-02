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

/** Three-cycle integer multiply; one shared signed 33x33 product. */
class MluUnit(p: XJParams) extends Module {
  val io = IO(new ScalarUnitIO(p))
  private val idle :: multiply :: finish :: complete :: Nil = Enum(4)
  private val state = RegInit(idle)
  private val a = Reg(SInt(33.W))
  private val b = Reg(SInt(33.W))
  private val high = Reg(Bool())
  private val product = Reg(SInt(66.W))
  private val result = Reg(new ExeResult(p))
  io.req.ready := state === idle && !io.flush
  io.resp.valid := state === complete && !io.flush
  io.resp.bits := result
  io.busy := state =/= idle
  when(io.flush) { state := idle }.otherwise {
    when(io.req.fire) {
      val op = io.req.bits.uop.op
      val x = io.req.bits.src1.value
      val y = io.req.bits.src2.value
      a := Cat((op === UOpCode.MULH || op === UOpCode.MULHSU) && x(31), x).asSInt
      b := Cat(op === UOpCode.MULH && y(31), y).asSInt
      high := op =/= UOpCode.MUL
      result := 0.U.asTypeOf(new ExeResult(p))
      result.tag := io.req.bits.uop.tag
      result.seq := io.req.bits.uop.seq
      result.nextPc := io.req.bits.uop.pc + 4.U
      state := multiply
    }
    when(state === multiply) { product := a * b; state := finish }
    when(state === finish) {
      result.data := Mux(high, product(63, 32), product(31, 0))
      state := complete
    }
    when(io.resp.fire) { state := idle }
  }
}

/** Radix-2 restoring divide: input capture, 32 steps, sign/exception finish.
  * No combinational / or %; quotient and remainder share the same datapath.
  */
class DvuUnit(p: XJParams) extends Module {
  val io = IO(new ScalarUnitIO(p))
  private val idle :: iterate :: finish :: complete :: Nil = Enum(4)
  private val state = RegInit(idle)
  private val quotient = Reg(UInt(32.W))
  private val remainder = Reg(UInt(32.W))
  private val divisor = Reg(UInt(32.W))
  private val original = Reg(UInt(32.W))
  private val count = Reg(UInt(5.W))
  private val quotientNegative = Reg(Bool())
  private val remainderNegative = Reg(Bool())
  private val zero = Reg(Bool())
  private val isRem = Reg(Bool())
  private val result = Reg(new ExeResult(p))
  io.req.ready := state === idle && !io.flush
  io.resp.valid := state === complete && !io.flush
  io.resp.bits := result
  io.busy := state =/= idle
  val trial = Cat(remainder, quotient(31))
  val subtract = trial >= Cat(0.U(1.W), divisor)
  val nextRemainder = Mux(subtract, trial - divisor, trial)
  when(io.flush) { state := idle }.otherwise {
    when(io.req.fire) {
      val op = io.req.bits.uop.op
      val a = io.req.bits.src1.value
      val b = io.req.bits.src2.value
      val signed = op === UOpCode.DIV || op === UOpCode.REM
      quotient := Mux(signed && a(31), -a, a)
      divisor := Mux(signed && b(31), -b, b)
      original := a
      quotientNegative := signed && (a(31) ^ b(31))
      remainderNegative := signed && a(31)
      zero := b === 0.U
      isRem := op === UOpCode.REM || op === UOpCode.REMU
      remainder := 0.U
      count := 0.U
      result := 0.U.asTypeOf(new ExeResult(p))
      result.tag := io.req.bits.uop.tag
      result.seq := io.req.bits.uop.seq
      result.nextPc := io.req.bits.uop.pc + 4.U
      state := iterate
    }
    when(state === iterate) {
      quotient := Cat(quotient(30, 0), subtract)
      remainder := nextRemainder(31, 0)
      count := count + 1.U
      when(count === 31.U) { state := finish }
    }
    when(state === finish) {
      val q = Mux(quotientNegative, -quotient, quotient)
      val r = Mux(remainderNegative, -remainder, remainder)
      result.data := Mux(isRem, Mux(zero, original, r), Mux(zero, "hffffffff".U, q))
      state := complete
    }
    when(io.resp.fire) { state := idle }
  }
}

/** Scalar FP32 core: the F-extension selection.  Executes with the instruction
  * rounding mode or the dispatch-time frm snapshot; candidate exception flags
  * ride the completion record and merge into fflags at commit.
  */
class FloatCore(p: XJParams) extends Module {
  val io = IO(new ScalarUnitIO(p))
  private val idle :: execute :: complete :: Nil = Enum(3)
  private val state = RegInit(idle)
  private val request = Reg(new IssueEntry(p))
  private val result = Reg(new ExeResult(p))
  private val count = Reg(UInt(3.W))
  private val uop = request.uop
  private val a = request.src1.value
  private val fa = request.fsrc1.value
  private val fb = request.fsrc2.value
  private val rm = Mux(uop.rm === 7.U, request.frm, uop.rm)
  io.req.ready := state === idle && !io.flush
  io.resp.valid := state === complete && !io.flush
  io.resp.bits := result
  io.busy := state =/= idle
  private val outResult = WireDefault(0.U(32.W))
  private val outFlags = WireDefault(0.U(5.W))
  private def apply(op: (UInt, UInt)): Unit = {
    outResult := op._1
    outFlags := op._2
  }
  // Build pipeline registers outside conditional scopes: every stage advances
  // together; only completion.valid gives their data architectural meaning.
  private val add = VectorFloat.f32Add(fa,
    Cat(fb(31) ^ (uop.op === UOpCode.FSUB), fb(30, 0)), rm, pipelined = true)
  private val mul = VectorFloat.f32Mul(fa, fb, rm, pipelined = true)
  switch(uop.op) {
    is(UOpCode.FADD, UOpCode.FSUB) { apply(add) }
    is(UOpCode.FMUL) { apply(mul) }
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
    is(UOpCode.FCVTSW) { apply(VectorFloat.intToF32(a, rm, false)) }
    is(UOpCode.FCVTSWU) { apply(VectorFloat.intToF32(a, rm, true)) }
    is(UOpCode.FMVXW) {
      outResult := fa
      outFlags := 0.U
    }
    is(UOpCode.FMVWX) {
      outResult := a
      outFlags := 0.U
    }
  }
  when(io.flush) { state := idle }.otherwise {
    when(io.req.fire) {
      request := io.req.bits
      count := 0.U
      state := execute
    }
    when(state === execute) {
      count := count + 1.U
      // Input register + four arithmetic boundaries + registered completion.
      when(count === 4.U) {
        result := 0.U.asTypeOf(new ExeResult(p))
        result.tag := uop.tag
        result.seq := uop.seq
        result.nextPc := uop.pc + 4.U
        result.data := outResult
        result.fflags := outFlags
        state := complete
      }
    }
    when(io.resp.fire) { state := idle }
  }
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
