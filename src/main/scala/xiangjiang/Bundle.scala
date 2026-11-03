package xiangjiang

import chisel3._
import chisel3.util._

object UOpCode extends ChiselEnum {
  val ADD, SUB, AND, OR, XOR, SLL, SRL, SRA, SLT, SLTU,
      ADDI, ANDI, ORI, XORI, SLLI, SRLI, SRAI, SLTI, SLTIU,
      LUI, AUIPC, LW, SW, BEQ, BNE, BLT, BGE, BLTU, BGEU,
      JAL, JALR, FENCE, HALT, VECTOR, MATRIX, DSETSIZE, DSETSTRIDE, MVIN, MVOUT = Value
}

object FuType extends ChiselEnum {
  val alu, mem, bru, system, vector, matrix, dmaConfig = Value
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
  val vectorLoadAlign = 8.U(4.W)
  val vectorLoadAccess = 9.U(4.W)
  val vectorStoreAlign = 10.U(4.W)
  val vectorStoreAccess = 11.U(4.W)
  val matrixAlign = 12.U(4.W)
  val matrixAccess = 13.U(4.W)
  val matrixState = 14.U(4.W)
  val dmaConfig = 15.U(5.W)
  val dmaRead = 16.U(5.W)
  val dmaWrite = 17.U(5.W)
  val dmaProtocol = 18.U(5.W)
}

/** Internal execution identifiers, not machine encodings. Paired operations
  * use even/odd identifiers for vv/vx; DecodeUnit translates XJ-ISA funct6.
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
  val fredsum = 61
  val fredmax = 62

  // Internal conversion selector (MicroOp.vecUnary), not an architectural source.
  val uWiden = 0
  val uNarrow = 1
  val uIntToFloat = 2
  val uFloatToInt = 3
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
  val vecUnary = UInt(2.W)
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
  val cause = UInt(5.W)
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
  val value = UInt(p.vectorBits.W)
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
  val data = UInt(p.vectorBits.W)
  val exception = Bool()
  val cause = UInt(5.W)
  val badAddr = UInt(32.W)
  val detail = UInt(32.W)
  val nextPc = UInt(32.W)
}

class CommitRecord(p: XJParams) extends Bundle {
  val uop = new MicroOp(p)
  val data = UInt(p.vectorBits.W)
  val exception = Bool()
  val cause = UInt(5.W)
  val badAddr = UInt(32.W)
  val detail = UInt(32.W)
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
  val error = Bool()
}

/** Parameters for one ordered matrix task handed from LSU to MatrixUnit. */
class MatrixTask(p: XJParams) extends Bundle {
  val tag = new RobTag(p)
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
  val faultCause = UInt(5.W)
  val faultAddr = UInt(32.W)
  val faultDetail = UInt(32.W)
  val dma = new DmaStatus
}

class DmaStatus extends Bundle {
  val rowBytes = UInt(32.W)
  val rows = UInt(32.W)
  val externalStride = UInt(32.W)
  val localStride = UInt(32.W)
  val bytesDone = UInt(32.W)
  val rowsDone = UInt(32.W)
  val active = Bool()
  val outbound = Bool()
  val error = Bool()
  val poisoned = Bool()
}

object TagMatch {
  def apply(a: RobTag, b: RobTag): Bool = a.asUInt === b.asUInt
}
