package xiangjiang

import chisel3._
import chisel3.util._

object UOpCode extends ChiselEnum {
  val ADD, SUB, AND, OR, XOR, SLL, SRL, SRA, SLT, SLTU,
      ADDI, ANDI, ORI, XORI, SLLI, SRLI, SRAI, SLTI, SLTIU,
      LUI, AUIPC, LW, SW, BEQ, BNE, BLT, BGE, BLTU, BGEU,
      JAL, JALR, FENCE, FENCEI, HALT, VECTOR, MATRIX, DMA,
      MUL, MULH, MULHSU, MULHU, DIV, DIVU, REM, REMU,
      CLZ, CTZ, CPOP, ANDN, ORN, XNOR, MIN, MINU, MAX, MAXU,
      SEXTB, SEXTH, ZEXTH, ROL, ROR, RORI, REV8, ORCB,
      FADD, FSUB, FMUL, FDIV, FSQRT, FMADD, FMSUB, FNMSUB, FNMADD,
      FEQ, FLT, FLE, FCVTWS, FCVTWUS, FCVTSW, FCVTSWU, FMVXW, FMVWX,
      FLW, FSW, CSR, VSETVL, VSETVLI, VSETIVLI, EBREAK, ECALL = Value
}

object FuType extends ChiselEnum {
  val alu, mem, bru, system, vector, matrix, mlu, dvu, fpu, csr = Value
}

/** Matrix ISA operation identifiers.  MLOAD/MSTORE are memory-class tasks,
  * MMUL/MMACC/MEW/MTRANS are compute tasks, and MBARRIER/MFENCE are ordered
  * memory-barrier tasks.  The removed V1-era MWAIT is not part of the XJ-NPU
  * architecture and decodes as an illegal instruction.
  */
object MatOp extends ChiselEnum {
  val msetmode, mload, mstore, mmul, mmacc, mew, mtrans, mbarrier, mfence = Value
}

// Local host fault codes, not privileged RISC-V mcause CSRs.
object FaultCode {
  val none = 0.U(5.W)
  val illegal = 1.U(5.W)
  val fetchAlign = 2.U(5.W)
  val fetchAccess = 3.U(5.W)
  val loadAlign = 4.U(5.W)
  val loadAccess = 5.U(5.W)
  val storeAlign = 6.U(5.W)
  val storeAccess = 7.U(5.W)
  // Matrix faults are intentionally distinct from scalar load/store codes.
  val matrixLoadAlign = 8.U(5.W)
  val matrixLoadAccess = 9.U(5.W)
  val matrixStoreAlign = 10.U(5.W)
  val matrixStoreAccess = 11.U(5.W)
  val dmaConfig = 12.U(5.W)
  val dmaRead = 13.U(5.W)
  val dmaWrite = 14.U(5.W)
  val dmaProtocol = 15.U(5.W)
  val ebreak = 16.U(5.W)
  val ecall = 17.U(5.W)
  val illegalCsr = 18.U(5.W)
  val frmIllegal = 19.U(5.W)
  val vtypeVill = 20.U(5.W)
  val vstartActive = 21.U(5.W)
}

/** Implementation-defined vector operation identifiers.
  *
  * The low two bits of the identifier select the vector opcode group and the
  * remaining bits form {inst[25], funct3}. For the paired arithmetic, logical,
  * comparison and floating-point operations an even identifier is the
  * vector-vector form and an odd identifier is the vector-scalar form.
  * Permutation, conversion, reduction and memory identifiers are standalone.
  *
  * Codes 0..63 use the legacy groups (OP-V and the AMO opcode); codes 64..127
  * use the custom-1 funct3 4..7 groups and carry the vtype-controlled FP32
  * operations and the relocated vector memory operations.
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

  // Legacy V1 BF16 vector operations (fixed 8 x BF16 lanes over the 128-bit
  // register; not vtype-controlled and not part of the FP32 vector set).
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

  // Relocated vtype-controlled vector memory operations.
  val vle32 = 64
  val vse32 = 65
  val vle16 = 66
  val vse16 = 67
  val vle8 = 68
  val vse8 = 69

  // FP32 vector arithmetic (vtype-controlled; SEW must be 32).
  val vfadd32 = 72
  val vfadd32s = 73
  val vfsub32 = 74
  val vfsub32s = 75
  val vfmul32 = 76
  val vfmul32s = 77
  val vfdiv32 = 78
  val vfdiv32s = 79
  val vfmacc32 = 80
  val vfmacc32s = 81
  val vfsqrt32 = 82
  val vfmax32 = 84
  val vfmax32s = 85
  val vfmin32 = 86
  val vfmin32s = 87
  val vfeq32 = 88
  val vfeq32s = 89
  val vflt32 = 90
  val vflt32s = 91
  val vfle32 = 92
  val vfle32s = 93
  val vfcvtF2I32 = 94
  val vfcvtF2U32 = 95
  val vfcvtI2F32 = 96
  val vfcvtU2F32 = 97
  val vfredsum32 = 98
  val vfredmax32 = 99

  // Selector values carried in rs1 for VecOp.vunary (legacy BF16 lane ops).
  val uWiden = 0
  val uNarrow = 1
  val uIntToFloat = 2
  val uFloatToInt = 3
  val uFredSum = 4
  val uFredMax = 5
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
  val rs3 = UInt(5.W)
  val rd = UInt(5.W)
  val usesRs1 = Bool()
  val usesRs2 = Bool()
  val usesRs3 = Bool()
  val writesRd = Bool()
  val frs1 = UInt(5.W)
  val frs2 = UInt(5.W)
  val frs3 = UInt(5.W)
  val usesF1 = Bool()
  val usesF2 = Bool()
  val usesF3 = Bool()
  val writesF = Bool()
  val rm = UInt(3.W)
  val csrAddr = UInt(12.W)
  val csrF3 = UInt(3.W)
  val vecOp = UInt(7.W)
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
  val dmaOp = UInt(2.W)
  val barrier = Bool()
  // Dispatch-time vector configuration snapshots.
  val vl = UInt(32.W)
  val vsew = UInt(2.W)
  val vlmul = UInt(3.W)
  val vstart = UInt(32.W)
  val vxrm = UInt(2.W)
  val vtypei = UInt(11.W)
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
  val value = UInt(p.vlen.W)
  val tag = new RobTag(p)
}

class IssueEntry(p: XJParams) extends Bundle {
  val uop = new MicroOp(p)
  val src1 = new Operand(p)
  val src2 = new Operand(p)
  val fsrc1 = new Operand(p)
  val fsrc2 = new Operand(p)
  val fsrc3 = new Operand(p)
  val vsrc1 = new VectorOperand(p)
  val vsrc2 = new VectorOperand(p)
  val vold = new VectorOperand(p)
  val vmask = new VectorOperand(p)
  val frm = UInt(3.W)
}

class ExeResult(p: XJParams) extends Bundle {
  val tag = new RobTag(p)
  val seq = UInt(p.seqBits.W)
  val data = UInt(p.vlen.W)
  val fflags = UInt(5.W)
  val csrWrEn = Bool()
  val csrAddr = UInt(12.W)
  val csrData = UInt(32.W)
  val exception = Bool()
  val cause = UInt(5.W)
  val badAddr = UInt(32.W)
  val nextPc = UInt(32.W)
}

class CommitRecord(p: XJParams) extends Bundle {
  val uop = new MicroOp(p)
  val data = UInt(p.vlen.W)
  val fflags = UInt(5.W)
  val csrWrEn = Bool()
  val csrAddr = UInt(12.W)
  val csrData = UInt(32.W)
  val exception = Bool()
  val cause = UInt(5.W)
  val badAddr = UInt(32.W)
  val nextPc = UInt(32.W)
}

class FetchPacket extends Bundle {
  val pc = UInt(32.W)
  val inst = UInt(32.W)
  val exception = Bool()
  val cause = UInt(5.W)
  val badAddr = UInt(32.W)
}

/** Shared 64-byte matrix/DMA DTCM beat. Addresses are 64-byte aligned and
  * the full physical row must lie inside DTCM. Matrix writes cover all bytes;
  * DMA uses the byte mask to preserve bytes outside the current burst.
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

/** DMA fields are unsigned byte counts/addresses; strides are row-start deltas. */
class DmaShape extends Bundle {
  val rowBytes = UInt(32.W)
  val rows = UInt(32.W)
  val externalStride = UInt(32.W)
  val localStride = UInt(32.W)
}
class DmaCommand extends Bundle {
  val write = Bool() // true: DTCM -> DDR
  val external = UInt(32.W)
  val local = UInt(32.W)
  val shape = new DmaShape
}
class DmaCompletion extends Bundle {
  val cause = UInt(5.W)
  val address = UInt(32.W)
  val bytesCompleted = UInt(64.W)
  val partial = Bool()
}

/** Parameters for one ordered matrix task handed from LSU to MatrixUnit. */
class MatrixTask(p: XJParams) extends Bundle {
  val tag = new RobTag(p)
  val op = MatOp()
  val unit = UInt(2.W)
  val buffer = UInt(2.W)
  val addr = UInt(32.W)
}

/** Commit-time publication of one matrix instruction: the ROB head releases
  * the instruction's staging record into the committed Matrix Architectural
  * State (MASF).
  */
class MatrixPublish(p: XJParams) extends Bundle {
  val tag = new RobTag(p)
  val op = MatOp()
  val unit = UInt(2.W)
  val buffer = UInt(2.W)
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
}

object TagMatch {
  def apply(a: RobTag, b: RobTag): Bool = a.asUInt === b.asUInt
}
