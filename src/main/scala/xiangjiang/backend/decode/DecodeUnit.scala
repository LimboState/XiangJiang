package xiangjiang.backend.decode

import chisel3._
import chisel3.util._
import xiangjiang._

/** Strict scalar/vector/matrix decode; exception records still enter the ROB.
  * Supports the rv32im_zicsr_zifencei_zbb baseline plus the documented F and
  * vector selections (see docs/XJ-ISA-Support.md). Unsupported encodings are
  * illegal instructions, never NOPs.
  */
class DecodeUnit(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val in = Input(new FetchPacket)
    val out = Output(new MicroOp(p))
  })

  val inst = io.in.inst
  val opcode = inst(6, 0)
  val funct3 = inst(14, 12)
  val funct7 = inst(31, 25)
  val rs2Field = inst(24, 20)
  val rm = inst(14, 12)
  val legal = WireDefault(false.B)
  val destination = WireDefault(false.B)
  val barrierField = WireDefault(false.B)
  val immI = Cat(Fill(20, inst(31)), inst(31, 20))
  val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
  val immB = Cat(Fill(19, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
  val immU = Cat(inst(31, 12), 0.U(12.W))
  val immJ = Cat(Fill(11, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))

  io.out := 0.U.asTypeOf(new MicroOp(p))
  io.out.inst := inst
  io.out.pc := io.in.pc
  io.out.rs1 := inst(19, 15)
  io.out.rs2 := rs2Field
  io.out.rs3 := inst(31, 27)
  io.out.rd := inst(11, 7)
  io.out.frs1 := inst(19, 15)
  io.out.frs2 := rs2Field
  io.out.frs3 := inst(31, 27)
  io.out.op := UOpCode.ADD
  io.out.fu := FuType.alu
  io.out.rm := rm

  def decode(op: UOpCode.Type, fu: FuType.Type, rs1: Boolean, rs2: Boolean,
             rd: Boolean, imm: UInt, useImm: Boolean): Unit = {
    legal := true.B
    io.out.op := op
    io.out.fu := fu
    io.out.usesRs1 := rs1.B
    io.out.usesRs2 := rs2.B
    destination := rd.B
    io.out.imm := imm
    io.out.useImm := useImm.B
  }

  val registerOps = Seq(
    (0, 0x00, UOpCode.ADD), (0, 0x20, UOpCode.SUB),
    (7, 0x00, UOpCode.AND), (6, 0x00, UOpCode.OR), (4, 0x00, UOpCode.XOR),
    (1, 0x00, UOpCode.SLL), (5, 0x00, UOpCode.SRL), (5, 0x20, UOpCode.SRA),
    (2, 0x00, UOpCode.SLT), (3, 0x00, UOpCode.SLTU))
  for ((f3, f7, op) <- registerOps) {
    when(opcode === "h33".U && funct3 === f3.U && funct7 === f7.U) {
      decode(op, FuType.alu, true, true, true, 0.U, false)
    }
  }
  val immediateOps = Seq(
    (0, UOpCode.ADDI), (7, UOpCode.ANDI), (6, UOpCode.ORI),
    (4, UOpCode.XORI), (2, UOpCode.SLTI), (3, UOpCode.SLTIU))
  for ((f3, op) <- immediateOps) {
    when(opcode === "h13".U && funct3 === f3.U) {
      decode(op, FuType.alu, true, false, true, immI, true)
    }
  }
  for ((f3, f7, op) <- Seq((1, 0x00, UOpCode.SLLI), (5, 0x00, UOpCode.SRLI), (5, 0x20, UOpCode.SRAI))) {
    when(opcode === "h13".U && funct3 === f3.U && funct7 === f7.U) {
      decode(op, FuType.alu, true, false, true, Cat(0.U(27.W), inst(24, 20)), true)
    }
  }
  when(opcode === "h37".U) { decode(UOpCode.LUI, FuType.alu, false, false, true, immU, true) }
  when(opcode === "h17".U) { decode(UOpCode.AUIPC, FuType.alu, false, false, true, immU, true) }

  // M extension: MUL/MULH/MULHSU/MULHU, DIV/DIVU/REM/REMU.
  val mOps = Seq(
    (0, UOpCode.MUL), (1, UOpCode.MULH), (2, UOpCode.MULHSU), (3, UOpCode.MULHU),
    (4, UOpCode.DIV), (5, UOpCode.DIVU), (6, UOpCode.REM), (7, UOpCode.REMU))
  for ((f3, op) <- mOps) {
    when(opcode === "h33".U && funct3 === f3.U && funct7 === 1.U) {
      decode(op, if (f3 <= 3) FuType.mlu else FuType.dvu, true, true, true, 0.U, false)
    }
  }

  // Zbb selection (see docs/XJ-ISA-Support.md for the complete list).
  for ((f7, f3, op) <- Seq(
    (0x20, 7, UOpCode.ANDN), (0x20, 6, UOpCode.ORN), (0x20, 4, UOpCode.XNOR),
    (0x05, 4, UOpCode.MIN), (0x05, 5, UOpCode.MINU), (0x05, 6, UOpCode.MAX),
    (0x05, 7, UOpCode.MAXU), (0x30, 5, UOpCode.ROR))) {
    when(opcode === "h33".U && funct3 === f3.U && funct7 === f7.U) {
      decode(op, FuType.alu, true, true, true, 0.U, false)
    }
  }
  for ((f7, f3, rs2v, op) <- Seq(
    (0x04, 4, 0x00, UOpCode.ZEXTH))) {
    when(opcode === "h33".U && funct3 === f3.U && funct7 === f7.U && rs2Field === rs2v.U) {
      decode(op, FuType.alu, true, true, true, 0.U, false)
    }
  }
  // ROL rd, rs1, rs2: funct7=0110000, funct3=001; rs2 selects the rotate
  // amount register.  rs2 0/1/2/4/5 are the CLZ/CTZ/CPOP/SEXT.B/SEXT.H
  // encodings instead.
  when(opcode === "h33".U && funct3 === 1.U && funct7 === 0x30.U &&
    rs2Field =/= 0.U && rs2Field =/= 1.U && rs2Field =/= 2.U &&
    rs2Field =/= 4.U && rs2Field =/= 5.U) {
    decode(UOpCode.ROL, FuType.alu, true, true, true, 0.U, false)
  }
  for ((f7, f3, rs2v, op) <- Seq(
    (0x30, 1, 0x00, UOpCode.CLZ), (0x30, 1, 0x01, UOpCode.CTZ), (0x30, 1, 0x02, UOpCode.CPOP),
    (0x30, 1, 0x04, UOpCode.SEXTB), (0x30, 1, 0x05, UOpCode.SEXTH),
    (0x34, 5, 0x18, UOpCode.REV8), (0x14, 5, 0x07, UOpCode.ORCB))) {
    when(opcode === "h13".U && funct3 === f3.U && funct7 === f7.U && rs2Field === rs2v.U) {
      val shamt = Cat(0.U(27.W), inst(24, 20))
      decode(op, FuType.alu, true, false, true, shamt, true)
    }
  }
  when(opcode === "h13".U && funct3 === 5.U && funct7 === 0x30.U && rs2Field =/= 0x18.U) {
    decode(UOpCode.RORI, FuType.alu, true, false, true, Cat(0.U(27.W), inst(24, 20)), true)
  }

  for ((f3, op) <- Seq((2, UOpCode.LW))) {
    when(opcode === "h03".U && funct3 === f3.U) {
      decode(op, FuType.mem, true, false, true, immI, true)
    }
  }
  when(opcode === "h23".U && funct3 === 2.U) {
    decode(UOpCode.SW, FuType.mem, true, true, false, immS, true)
  }
  for ((f3, op) <- Seq((0, UOpCode.BEQ), (1, UOpCode.BNE), (4, UOpCode.BLT),
                       (5, UOpCode.BGE), (6, UOpCode.BLTU), (7, UOpCode.BGEU))) {
    when(opcode === "h63".U && funct3 === f3.U) {
      decode(op, FuType.bru, true, true, false, immB, false)
    }
  }
  when(opcode === "h6f".U) { decode(UOpCode.JAL, FuType.bru, false, false, true, immJ, true) }
  when(opcode === "h67".U && funct3 === 0.U) { decode(UOpCode.JALR, FuType.bru, true, false, true, immI, true) }
  // All fm/pred/succ/rs1/rd values conservatively receive the full barrier.
  when(opcode === "h0f".U && funct3 === 0.U) {
    decode(UOpCode.FENCE, FuType.system, false, false, false, 0.U, false)
  }
  when(opcode === "h0f".U && funct3 === 1.U) {
    decode(UOpCode.FENCEI, FuType.system, false, false, false, 0.U, false)
  }
  // Only the exact custom-0 word is HALT.  Every other custom-0 encoding,
  // including the all-zero word, is illegal.
  when(inst === "h0000000b".U) {
    decode(UOpCode.HALT, FuType.system, false, false, false, 0.U, false)
  }

  // F extension selection: FLW/FSW.
  when(opcode === "h07".U && funct3 === 2.U) {
    legal := true.B
    io.out.op := UOpCode.FLW
    io.out.fu := FuType.mem
    io.out.usesRs1 := true.B
    io.out.writesF := true.B
    io.out.imm := immI
    io.out.useImm := true.B
  }
  when(opcode === "h27".U && funct3 === 2.U) {
    legal := true.B
    io.out.op := UOpCode.FSW
    io.out.fu := FuType.mem
    io.out.usesRs1 := true.B
    io.out.usesF2 := true.B
    io.out.imm := immS
    io.out.useImm := true.B
  }

  // F arithmetic: rm must be a legal static rounding mode or DYN.
  val rmLegal = rm <= 4.U || rm === 7.U
  for ((f7, op) <- Seq((0x00, UOpCode.FADD), (0x04, UOpCode.FSUB),
                       (0x08, UOpCode.FMUL), (0x0c, UOpCode.FDIV))) {
    when(opcode === "h53".U && funct7 === f7.U && rmLegal) {
      legal := true.B
      io.out.op := op
      io.out.fu := FuType.fpu
      io.out.usesF1 := true.B
      io.out.usesF2 := true.B
      io.out.writesF := true.B
    }
  }
  when(opcode === "h53".U && funct7 === 0x2c.U && rs2Field === 0.U && rmLegal) {
    legal := true.B
    io.out.op := UOpCode.FSQRT
    io.out.fu := FuType.fpu
    io.out.usesF1 := true.B
    io.out.writesF := true.B
  }
  for ((f7, op) <- Seq((0x50, UOpCode.FEQ), (0x50, UOpCode.FLT), (0x50, UOpCode.FLE))) {
    val f3v = op match {
      case UOpCode.FEQ => 2
      case UOpCode.FLT => 1
      case _ => 0
    }
    when(opcode === "h53".U && funct7 === f7.U && funct3 === f3v.U) {
      legal := true.B
      io.out.op := op
      io.out.fu := FuType.fpu
      io.out.usesF1 := true.B
      io.out.usesF2 := true.B
      destination := true.B
    }
  }
  for ((f7, rs2v, op) <- Seq((0x60, 0, UOpCode.FCVTWS), (0x60, 1, UOpCode.FCVTWUS),
                             (0x68, 0, UOpCode.FCVTSW), (0x68, 1, UOpCode.FCVTSWU))) {
    when(opcode === "h53".U && funct7 === f7.U && rs2Field === rs2v.U && rmLegal) {
      legal := true.B
      io.out.op := op
      io.out.fu := FuType.fpu
      if (f7 == 0x60) {
        io.out.usesF1 := true.B
        destination := true.B
      } else {
        io.out.usesRs1 := true.B
        io.out.writesF := true.B
      }
    }
  }
  when(opcode === "h53".U && funct7 === 0x70.U && rs2Field === 0.U) {
    legal := true.B
    io.out.op := UOpCode.FMVXW
    io.out.fu := FuType.fpu
    io.out.usesF1 := true.B
    destination := true.B
  }
  when(opcode === "h53".U && funct7 === 0x78.U && rs2Field === 0.U) {
    legal := true.B
    io.out.op := UOpCode.FMVWX
    io.out.fu := FuType.fpu
    io.out.usesRs1 := true.B
    io.out.writesF := true.B
  }
  for ((opcodeF, op) <- Seq(("h43".U, UOpCode.FMADD), ("h47".U, UOpCode.FMSUB),
                            ("h4b".U, UOpCode.FNMSUB), ("h4f".U, UOpCode.FNMADD))) {
    when(opcode === opcodeF && rmLegal) {
      legal := true.B
      io.out.op := op
      io.out.fu := FuType.fpu
      io.out.usesF1 := true.B
      io.out.usesF2 := true.B
      io.out.usesF3 := true.B
      io.out.writesF := true.B
    }
  }

  // Zicsr: CSRRW/CSRRS/CSRRC and immediate forms.  Read-only CSR writes are
  // illegal instructions; writes to context CSRs are dispatch barriers.
  val csrImmediate = funct3 >= 5.U
  val csrLegalF3 = funct3 === 1.U || funct3 === 2.U || funct3 === 3.U ||
    funct3 === 5.U || funct3 === 6.U || funct3 === 7.U
  val contextCsr = inst(31, 20) === 0x002.U || inst(31, 20) === 0x003.U ||
    inst(31, 20) === 0x008.U || inst(31, 20) === 0x00a.U || inst(31, 20) === 0x00f.U
  when(opcode === "h73".U && csrLegalF3) {
    legal := true.B
    io.out.op := UOpCode.CSR
    io.out.fu := FuType.csr
    io.out.usesRs1 := !csrImmediate
    io.out.usesRs2 := false.B
    destination := true.B
    io.out.csrAddr := inst(31, 20)
    io.out.csrF3 := funct3
    io.out.imm := Cat(0.U(27.W), inst(19, 15))
    io.out.useImm := csrImmediate
    barrierField := contextCsr
  }

  // vset* configuration instructions execute at the ROB head.  Encodings
  // follow the RISC-V V spec: vsetvli has bit31=0 (vtypei in bits 30:20),
  // vsetvl has bit31=1 with bits 30:25 zero (vtype from rs2), and vsetivli
  // has bits 31:30 = 11 (vtypei[9:0] in bits 29:20).
  val vsetFunct = opcode === "h57".U && funct3 === 7.U
  val vsetvlForm = vsetFunct && inst(31) && inst(30, 25) === 0.U
  val vsetivliForm = vsetFunct && inst(31, 30) === "b11".U
  val vsetvliForm = vsetFunct && !inst(31)
  when(vsetvliForm) {
    decode(UOpCode.VSETVLI, FuType.csr, true, false, true,
      Cat(0.U(21.W), inst(30, 20)), true)
    io.out.vtypei := inst(30, 20)
    barrierField := true.B
  }
  when(vsetivliForm) {
    decode(UOpCode.VSETIVLI, FuType.csr, false, false, true,
      Cat(0.U(27.W), inst(19, 15)), true)
    io.out.vtypei := Cat(0.U(1.W), inst(29, 20))
    barrierField := true.B
  }
  when(vsetvlForm) {
    decode(UOpCode.VSETVL, FuType.csr, true, true, true, 0.U, false)
    barrierField := true.B
  }

  // ECALL/EBREAK are precise host events delivered through the fault path
  // (docs/XJ-NPU.md 5.1); the service protocol is not yet frozen.
  when(inst === "h00000073".U) {
    decode(UOpCode.ECALL, FuType.system, false, false, false, 0.U, false)
  }
  when(inst === "h00100073".U) {
    decode(UOpCode.EBREAK, FuType.system, false, false, false, 0.U, false)
  }

  // Matrix instructions own custom-1/custom-2/custom-3 with funct3 <= 3.
  // Vector uses OP-V, the unused AMO opcode and the custom-1 funct3 4..7
  // groups. custom-1/custom-3 only carry vector encodings in funct3 4..7.
  val matrixDomain = opcode === "h5b".U ||
    ((opcode === "h2b".U || opcode === "h7b".U) && funct3 <= 3.U)
  val matUnitField = funct3(1, 0)
  val matUnitLegal = matUnitField =/= 3.U
  def decodeMatrix(matOp: MatOp.Type, fu: FuType.Type, rs1: Boolean,
                   barrier: Boolean): Unit = {
    legal := true.B
    io.out.op := UOpCode.MATRIX
    io.out.fu := fu
    io.out.usesRs1 := rs1.B
    io.out.usesRs2 := false.B
    io.out.matOp := matOp
    io.out.matUnit := matUnitField
    barrierField := barrier.B
  }
  when(matrixDomain) {
    when(opcode === "h2b".U) {
      when(!matUnitLegal) {
        legal := false.B
      }.elsewhen(inst(31, 25) === 0.U && inst(24, 21) === 0.U &&
        inst(19, 15) === 0.U && inst(11, 7) === 0.U) {
        decodeMatrix(MatOp.msetmode, FuType.system, false, true)
        io.out.matMode := inst(20)
        // The BF16 unit only supports output-stationary dataflow.
        when(matUnitField === 0.U && inst(20)) { legal := false.B }
      }.elsewhen(inst(31, 27) === 1.U && inst(26, 25) =/= 3.U &&
        inst(24, 20) === 0.U && inst(11, 7) === 0.U) {
        decodeMatrix(MatOp.mload, FuType.mem, true, false)
        io.out.matBuffer := inst(26, 25)
      }.elsewhen(inst(31, 25) === "b0001000".U && inst(24, 20) === 0.U &&
        inst(11, 7) === 0.U) {
        decodeMatrix(MatOp.mstore, FuType.mem, true, false)
      }
    }
    when(opcode === "h5b".U) {
      when(!matUnitLegal || inst(19, 15) =/= 0.U || inst(24, 20) =/= 0.U ||
        inst(11, 7) =/= 0.U || inst(31, 25) > 3.U) {
        legal := false.B
      }.otherwise {
        val computeOp = MuxLookup(inst(31, 25), MatOp.mmul)(Seq(
          0.U(7.W) -> MatOp.mmul, 1.U(7.W) -> MatOp.mmacc,
          2.U(7.W) -> MatOp.mew, 3.U(7.W) -> MatOp.mtrans))
        decodeMatrix(computeOp, FuType.matrix, false, false)
      }
    }
    when(opcode === "h7b".U) {
      // V1-era MWAIT (custom-3 funct3=0) is not part of XJ-NPU and is illegal.
      when(funct3 === 1.U && inst(31, 15) === 0.U && inst(11, 7) === 0.U) {
        decodeMatrix(MatOp.mfence, FuType.mem, false, true)
      }.elsewhen(funct3 === 2.U && inst(26, 25) === 0.U && inst(24, 20) === 0.U &&
        inst(19, 15) === 0.U && inst(11, 7) === 0.U) {
        decodeMatrix(MatOp.mbarrier, FuType.mem, false, true)
        io.out.matId := inst(31, 27)
      }.otherwise {
        legal := false.B
      }
    }
  }

  // Vector opcodes use OP-V, the AMO opcode and custom-1 funct3 4..7.
  val customOpcode = opcode === "h57".U || opcode === "h2f".U ||
    (opcode === "h2b".U && funct3 >= 4.U)
  val customIndex = MuxLookup(opcode, 0.U(3.W))(Seq(
    "h57".U -> 0.U,
    "h2f".U -> 1.U,
    "h2b".U -> (2.U +& funct3(1, 0))))
  val vecOp = Cat(inst(25), funct3, customIndex)
  val unary = vecOp === VecOp.vunary.U
  val selector = inst(19, 15)
  val reduction = unary && (selector === VecOp.uFredSum.U || selector === VecOp.uFredMax.U)
  val fp32Reduction = vecOp === VecOp.vfredsum32.U || vecOp === VecOp.vfredmax32.U
  val vectorMemory = vecOp >= VecOp.vle32.U && vecOp <= VecOp.vse8.U
  val vectorStore = vecOp === VecOp.vse32.U || vecOp === VecOp.vse16.U || vecOp === VecOp.vse8.U
  val merge = vecOp === VecOp.vmerge.U
  val legacyFp = vecOp >= VecOp.vfadd.U && vecOp <= (VecOp.vmfle + 1).U
  val fp32UnaryCvt = vecOp >= VecOp.vfcvtF2I32.U && vecOp <= VecOp.vfcvtU2F32.U
  val unaryLegal = selector <= VecOp.uFredMax.U
  val vecLegal = (vecOp <= VecOp.vunary.U && (!unary || unaryLegal)) ||
    vectorMemory || fp32Reduction || (vecOp >= VecOp.vfadd32.U && vecOp <= VecOp.vfredmax32.U)
  when(customOpcode && !matrixDomain && vsetFunct === false.B && vecLegal) {
    legal := true.B
    io.out.op := UOpCode.VECTOR
    io.out.fu := Mux(vectorMemory, FuType.mem, FuType.vector)
    io.out.vecOp := vecOp
    io.out.unmasked := inst(26)
    io.out.maskReg := inst(31, 27)
    io.out.usesRs1 := (vecOp < VecOp.vslideup.U && vecOp(0)) ||
      vecOp === VecOp.vslideup.U || vecOp === VecOp.vslidedown.U || vectorMemory
    io.out.usesV1 := (vecOp < VecOp.vslideup.U && !vecOp(0) && !fp32UnaryCvt && !fp32Reduction &&
      !(vecOp >= VecOp.vfadd32.U && vecOp <= VecOp.vfredmax32.U && vecOp(0))) ||
      merge || vecOp === VecOp.vrgather.U ||
      (vecOp >= VecOp.vfadd32.U && vecOp <= VecOp.vfredmax32.U && !vecOp(0) && !fp32UnaryCvt && !fp32Reduction)
    io.out.usesV2 := !vectorMemory
    io.out.usesF1 := vecOp >= VecOp.vfadd32.U && vecOp <= VecOp.vfredmax32.U && vecOp(0) &&
      !fp32UnaryCvt && !fp32Reduction
    io.out.usesVMask := !inst(26)
    // Every vector-writing instruction captures the old destination version
    // for tail/mask preservation; stores capture their data from vd.
    io.out.usesVOld := vectorStore || (!reduction && !fp32Reduction)
    io.out.writesV := !vectorStore && !reduction && !fp32Reduction
    destination := reduction || fp32Reduction
    io.out.imm := Mux(vectorMemory,
      Cat(Fill(27, inst(24)), inst(24, 20)), Cat(0.U(27.W), selector))
    io.out.useImm := vectorMemory || vecOp === VecOp.vslideupImm.U ||
      vecOp === VecOp.vslidedownImm.U
  }

  // DMA uses the otherwise reserved custom-1 funct3=3 space. Config
  // instructions are barriers but run through MemQ to capture scalar operands.
  when(opcode === "h2b".U && funct3 === 3.U && inst(11, 7) === 0.U && funct7 >= 16.U && funct7 <= 19.U) {
    decode(UOpCode.DMA, FuType.mem, true, true, false, 0.U, false)
    io.out.dmaOp := funct7(1, 0)
  }

  io.out.exception := io.in.exception || !legal
  io.out.cause := Mux(io.in.exception, io.in.cause,
    Mux(!legal, FaultCode.illegal, FaultCode.none))
  io.out.badAddr := Mux(io.in.exception, io.in.badAddr, Mux(!legal, inst, 0.U))
  io.out.writesRd := destination && io.out.rd =/= 0.U && !io.out.exception
  io.out.barrier := barrierField || io.out.fu === FuType.bru || io.out.fu === FuType.system ||
    io.out.exception || (io.out.op === UOpCode.DMA && !io.out.dmaOp(1))
  when(io.out.exception) {
    io.out.usesRs1 := false.B
    io.out.usesRs2 := false.B
    io.out.writesV := false.B
    io.out.usesV1 := false.B
    io.out.usesV2 := false.B
    io.out.usesVOld := false.B
    io.out.usesVMask := false.B
    io.out.writesF := false.B
    io.out.usesF1 := false.B
    io.out.usesF2 := false.B
    io.out.usesF3 := false.B
  }
}
