package xiangjiang

import chisel3._
import chisel3.util._

object UOpCode extends ChiselEnum {
  val ADD, SUB, AND, OR, XOR, SLL, SRL, SRA, SLT, SLTU,
      ADDI, ANDI, ORI, XORI, SLLI, SRLI, SRAI, SLTI, SLTIU,
      LUI, AUIPC, LW, SW, BEQ, BNE, BLT, BGE, BLTU, BGEU,
      JAL, JALR, FENCE, HALT, VECTOR, MATRIX = Value
}

object FuType extends ChiselEnum {
  val alu, mem, bru, system, vector, matrix = Value
}

/** Matrix ISA operation identifiers.  MLOAD/MSTORE are memory-class tasks,
  * MMUL/MMACC/MEW/MTRANS and MWAIT are ordered matrix tasks, and the remaining
  * operations are control barriers handled at the ROB head.
  */
object MatOp extends ChiselEnum {
  val msetmode, mload, mstore, mmul, mmacc, mew, mtrans, mwait, mbarrier, mfence = Value
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
  // Matrix faults are intentionally distinct from scalar load/store codes.
  val matrixLoadAlign = 8.U(4.W)
  val matrixLoadAccess = 9.U(4.W)
  val matrixStoreAlign = 10.U(4.W)
  val matrixStoreAccess = 11.U(4.W)
}

/** Implementation-defined vector operation identifiers.
  *
  * The vector encoding is intentionally not RVV-compatible.  The low two bits
  * of the identifier select one of four vector opcodes, bit 2..4 form funct3,
  * and the remaining bit is inst[25].  For the paired arithmetic, logical,
  * comparison and floating-point operations an even identifier is the
  * vector-vector form and an odd identifier is the vector-scalar form.
  * Permutation, conversion, reduction, special-function and memory identifiers
  * are standalone.
  */
object VecOp {
  val vadd = 0
  val vsub = 2
  val vmul = 4
  val vmax = 6
  val vmin = 8
  val vand = 10
  val vor = 12
  val vxor = 14
  val vsll = 16
  val vsrl = 18
  val vsra = 20

  val vfadd = 22
  val vfsub = 24
  val vfmul = 26
  val vfdiv = 28
  val vfmacc = 30
  val vfmax = 32
  val vfmin = 34

  val vmseq = 36
  val vmslt = 38
  val vmsle = 40
  val vmsltu = 42
  val vmsleu = 44
  val vmfeq = 46
  val vmflt = 48
  val vmfle = 50

  val vslideup = 52
  val vslideupImm = 53
  val vslidedown = 54
  val vslidedownImm = 55
  val vmerge = 56
  val vrgather = 57
  val vunary = 58
  val vle32 = 59
  val vse32 = 60
  val vle16 = 61
  val vse16 = 62

  // Selector values carried in rs1 for VecOp.vunary.
  val uWiden = 0
  val uNarrow = 1
  val uIntToFloat = 2
  val uFloatToInt = 3
  val uFredSum = 4
  val uFredMax = 5
  val uExp = 6
  val uRcp = 7
  val uRsqrt = 8
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
  val vecOp = UInt(6.W)
  val writesV = Bool()
  val usesV1 = Bool()
  val usesV2 = Bool()
  val usesVOld = Bool()
  val usesVMask = Bool()
  val maskReg = UInt(5.W)
  val unmasked = Bool()
  val imm = UInt(32.W)
  val useImm = Bool()
  val matOp = MatOp()
  val matUnit = UInt(2.W)
  val matBuffer = UInt(2.W)
  val matMode = Bool()
  val matId = UInt(5.W)
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

class VectorOperand(p: XJParams) extends Bundle {
  val ready = Bool()
  val value = UInt(512.W)
  val tag = new RobTag(p)
}

class IssueEntry(p: XJParams) extends Bundle {
  val uop = new MicroOp(p)
  val src1 = new Operand(p)
  val src2 = new Operand(p)
  val vsrc1 = new VectorOperand(p)
  val vsrc2 = new VectorOperand(p)
  val vold = new VectorOperand(p)
  val vmask = new VectorOperand(p)
}

class ExeResult(p: XJParams) extends Bundle {
  val tag = new RobTag(p)
  val seq = UInt(p.seqBits.W)
  val data = UInt(512.W)
  val exception = Bool()
  val cause = UInt(4.W)
  val badAddr = UInt(32.W)
  val nextPc = UInt(32.W)
}

class CommitRecord(p: XJParams) extends Bundle {
  val uop = new MicroOp(p)
  val data = UInt(512.W)
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

/** One 64-byte matrix DTCM beat.  Addresses are 64-byte aligned and the
  * full beat must lie inside DTCM; write beats always cover all 64 bytes.
  */
class MatrixBeatReq extends Bundle {
  val addr = UInt(32.W)
  val data = UInt(512.W)
  val write = Bool()
  val mask = UInt(64.W)
}

class MatrixBeatResp extends Bundle {
  val data = UInt(512.W)
}

/** Parameters for one ordered matrix task handed from LSU to MatrixUnit. */
class MatrixTask extends Bundle {
  val op = MatOp()
  val unit = UInt(2.W)
  val buffer = UInt(2.W)
  val addr = UInt(32.W)
}

/** MSETMODE configuration command from the commit controller. */
class MatrixConfig extends Bundle {
  val unit = UInt(2.W)
  val mode = Bool()
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
