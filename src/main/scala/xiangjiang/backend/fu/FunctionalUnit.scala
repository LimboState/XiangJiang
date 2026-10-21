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
