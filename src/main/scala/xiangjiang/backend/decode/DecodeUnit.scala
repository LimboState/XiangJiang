package xiangjiang.backend.decode

import chisel3._
import chisel3.util._
import xiangjiang._

/** Strict scalar/vector decode; exception records still enter the ROB. */
class DecodeUnit(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val in = Input(new FetchPacket)
    val out = Output(new MicroOp(p))
  })

  val inst = io.in.inst
  val opcode = inst(6, 0)
  val funct3 = inst(14, 12)
  val funct7 = inst(31, 25)
  val legal = WireDefault(false.B)
  val destination = WireDefault(false.B)
  val immI = Cat(Fill(20, inst(31)), inst(31, 20))
  val immS = Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7))
  val immB = Cat(Fill(19, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W))
  val immU = Cat(inst(31, 12), 0.U(12.W))
  val immJ = Cat(Fill(11, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))

  io.out := 0.U.asTypeOf(new MicroOp(p))
  io.out.inst := inst
  io.out.pc := io.in.pc
  io.out.rs1 := inst(19, 15)
  io.out.rs2 := inst(24, 20)
  io.out.rd := inst(11, 7)
  io.out.op := UOpCode.ADD
  io.out.fu := FuType.alu

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
  // HALT is an exact word; the remaining custom-0 space is checked below.
  when(inst === "h0000000b".U) {
    decode(UOpCode.HALT, FuType.system, false, false, false, 0.U, false)
  }

  val matrixDomain = opcode === "h5b".U || opcode === "h2b".U || opcode === "h7b".U
  val matUnitField = funct3(1, 0)
  val matUnitLegal = funct3 <= 2.U
  def decodeMatrix(matOp: MatOp.Type, fu: FuType.Type, rs1: Boolean,
                   barrier: Boolean): Unit = {
    legal := true.B
    io.out.op := UOpCode.MATRIX
    io.out.fu := fu
    io.out.usesRs1 := rs1.B
    io.out.usesRs2 := false.B
    io.out.matOp := matOp
    io.out.matUnit := matUnitField
    io.out.barrier := barrier.B
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
      when(funct3 === 0.U && inst(31, 30) =/= 3.U && inst(29, 25) === 0.U &&
        inst(24, 20) === 0.U && inst(19, 15) === 0.U && inst(11, 7) === 0.U) {
        decodeMatrix(MatOp.mwait, FuType.matrix, false, false)
        io.out.matUnit := inst(31, 30)
      }.elsewhen(funct3 === 1.U && inst(31, 15) === 0.U && inst(11, 7) === 0.U) {
        decodeMatrix(MatOp.mfence, FuType.system, false, true)
      }.elsewhen(funct3 === 2.U && inst(26, 25) === 0.U && inst(24, 20) === 0.U &&
        inst(19, 15) === 0.U && inst(11, 7) === 0.U) {
        decodeMatrix(MatOp.mbarrier, FuType.system, false, true)
        io.out.matId := inst(31, 27)
      }.otherwise {
        legal := false.B
      }
    }
  }

  // XJ-V: funct6 selects the operation, funct3 the operand form, vm=inst[25].
  val f6 = inst(31, 26)
  val paired = f6 <= "h11".U || (f6 >= "h1b".U && f6 <= "h22".U)
  val unary = f6 >= "h12".U && f6 <= "h15".U
  val slide = f6 === "h16".U || f6 === "h17".U
  val reduction = f6 === "h19".U || f6 === "h1a".U
  val merge = f6 === "h23".U
  val vectorLegal = (paired && (funct3 === 0.U || funct3 === 4.U)) ||
    (unary && funct3 === 2.U && inst(19, 15) === 0.U) ||
    (slide && (funct3 === 4.U || funct3 === 3.U)) ||
    (f6 === "h18".U && funct3 === 0.U) ||
    (reduction && funct3 === 1.U) || (merge && funct3 === 0.U && !inst(25))
  when(opcode === "h57".U && vectorLegal) {
    legal := true.B
    io.out.op := UOpCode.VECTOR
    io.out.fu := FuType.vector
    io.out.vecOp := Mux(f6 <= "h11".U, (f6 << 1) + (funct3 === 4.U),
      Mux(f6 >= "h1b".U && f6 <= "h22".U,
        VecOp.vmseq.U + ((f6 - "h1b".U) << 1) + (funct3 === 4.U),
        MuxLookup(f6, VecOp.vunary.U)(Seq(
          "h16".U -> Mux(funct3 === 3.U, VecOp.vslideupImm.U, VecOp.vslideup.U),
          "h17".U -> Mux(funct3 === 3.U, VecOp.vslidedownImm.U, VecOp.vslidedown.U),
          "h18".U -> VecOp.vrgather.U, "h19".U -> VecOp.fredsum.U,
          "h1a".U -> VecOp.fredmax.U, "h23".U -> VecOp.vmerge.U))))
    io.out.vecUnary := f6 - "h12".U
    io.out.unmasked := inst(25)
    io.out.maskReg := 0.U
    io.out.usesRs1 := funct3 === 4.U
    io.out.usesV1 := funct3 === 0.U || reduction
    io.out.usesV2 := true.B
    io.out.usesVMask := !inst(25)
    io.out.usesVOld := reduction || (!merge && (!inst(25) || f6 === "h0f".U))
    io.out.writesV := true.B
    io.out.imm := Cat(0.U(27.W), inst(19, 15))
    io.out.useImm := slide && funct3 === 3.U
  }
  // Unmasked whole-register transfers; VST's source is inst[24:20], not rd.
  when(opcode === "h0b".U && (funct3 === 1.U || funct3 === 2.U)) {
    val store = funct3 === 2.U
    legal := true.B
    io.out.op := UOpCode.VECTOR
    io.out.fu := FuType.mem
    io.out.vecOp := Mux(store, VecOp.vse32.U, VecOp.vle32.U)
    io.out.usesRs1 := true.B
    io.out.usesVOld := store
    io.out.writesV := !store
    io.out.unmasked := true.B
    io.out.imm := Mux(store, immS, immI)
    io.out.useImm := true.B
  }

  when(opcode === "h0b".U && funct7 === 0.U && inst(11, 7) === 0.U &&
    funct3 >= 3.U && funct3 <= 6.U) {
    val config = funct3 <= 4.U
    legal := true.B
    io.out.op := MuxLookup(funct3, UOpCode.MVIN)(Seq(
      3.U -> UOpCode.DSETSIZE, 4.U -> UOpCode.DSETSTRIDE,
      5.U -> UOpCode.MVIN, 6.U -> UOpCode.MVOUT))
    io.out.fu := Mux(config, FuType.dmaConfig, FuType.mem)
    io.out.usesRs1 := true.B
    io.out.usesRs2 := true.B
  }

  io.out.exception := io.in.exception || !legal
  io.out.cause := Mux(io.in.exception, io.in.cause,
    Mux(!legal, FaultCode.illegal, FaultCode.none))
  io.out.badAddr := Mux(io.in.exception, io.in.badAddr, Mux(!legal, inst, 0.U))
  io.out.writesRd := destination && io.out.rd =/= 0.U && !io.out.exception
  io.out.barrier := io.out.fu === FuType.bru || io.out.fu === FuType.system || io.out.fu === FuType.dmaConfig ||
    (io.out.op === UOpCode.MATRIX && io.out.matOp === MatOp.mwait) || io.out.exception
  when(io.out.exception) {
    io.out.usesRs1 := false.B
    io.out.usesRs2 := false.B
    io.out.writesV := false.B
    io.out.usesV1 := false.B
    io.out.usesV2 := false.B
    io.out.usesVOld := false.B
    io.out.usesVMask := false.B
  }
}
