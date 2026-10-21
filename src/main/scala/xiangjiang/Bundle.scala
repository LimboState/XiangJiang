package xiangjiang

import chisel3._
import chisel3.util._

object UOpCode extends ChiselEnum {
  val ADD, SUB, AND, OR, XOR, SLL, SRL, SRA, SLT, SLTU,
      ADDI, ANDI, ORI, XORI, SLLI, SRLI, SRAI, SLTI, SLTIU,
      LUI, AUIPC, LW, SW, BEQ, BNE, BLT, BGE, BLTU, BGEU,
      JAL, JALR, FENCE, HALT = Value
}

object FuType extends ChiselEnum {
  val alu, mem, bru, system = Value
}

// Local host fault codes, not privileged RISC-V mcause CSRs.
object FaultCode {
  val none = 0.U(4.W)
  val illegal = 1.U(4.W)
  val fetchAlign = 2.U(4.W)
  val fetchAccess = 3.U(4.W)
  val loadAlign = 4.U(4.W)
  val loadAccess = 5.U(4.W)
  val storeAlign = 6.U(4.W)
  val storeAccess = 7.U(4.W)
}

class RobTag(p: XJParams) extends Bundle {
  val slot = UInt(p.robBits.W)
  val generation = UInt(p.generationBits.W)
}

class MicroOp(p: XJParams) extends Bundle {
  val inst = UInt(32.W)
  val pc = UInt(32.W)
  val op = UOpCode()
  val fu = FuType()
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rd = UInt(5.W)
  val usesRs1 = Bool()
  val usesRs2 = Bool()
  val writesRd = Bool()
  val imm = UInt(32.W)
  val useImm = Bool()
  val barrier = Bool()
  val exception = Bool()
  val cause = UInt(4.W)
  val badAddr = UInt(32.W)
  val tag = new RobTag(p)
  val seq = UInt(p.seqBits.W)
}

class Operand(p: XJParams) extends Bundle {
  val ready = Bool()
  val value = UInt(32.W)
  val tag = new RobTag(p)
}

class IssueEntry(p: XJParams) extends Bundle {
  val uop = new MicroOp(p)
  val src1 = new Operand(p)
  val src2 = new Operand(p)
}

class ExeResult(p: XJParams) extends Bundle {
  val tag = new RobTag(p)
  val seq = UInt(p.seqBits.W)
  val data = UInt(32.W)
  val exception = Bool()
  val cause = UInt(4.W)
  val badAddr = UInt(32.W)
  val nextPc = UInt(32.W)
}

class CommitRecord(p: XJParams) extends Bundle {
  val uop = new MicroOp(p)
  val data = UInt(32.W)
  val exception = Bool()
  val cause = UInt(4.W)
  val badAddr = UInt(32.W)
  val nextPc = UInt(32.W)
}

class FetchPacket extends Bundle {
  val pc = UInt(32.W)
  val inst = UInt(32.W)
  val exception = Bool()
  val cause = UInt(4.W)
  val badAddr = UInt(32.W)
}

class WordReq extends Bundle {
  val addr = UInt(32.W)
  val data = UInt(32.W)
  val write = Bool()
  val mask = UInt(4.W)
}

class WordResp extends Bundle {
  val data = UInt(32.W)
  val error = Bool()
}

class CoreStatus extends Bundle {
  val busy = Bool()
  val done = Bool()
  val fault = Bool()
  val faultPc = UInt(32.W)
  val faultInst = UInt(32.W)
  val faultCause = UInt(4.W)
  val faultAddr = UInt(32.W)
}

object TagMatch {
  def apply(a: RobTag, b: RobTag): Bool = a.asUInt === b.asUInt
}
