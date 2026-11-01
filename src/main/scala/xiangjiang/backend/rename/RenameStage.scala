package xiangjiang.backend.rename

import chisel3._
import chisel3.util._
import xiangjiang._

/** Captures source versions before installing the current destination mapping.
  * Maintains the architectural register file and the X, F and V alias tables;
  * committed values live here and are updated only by the commit path.
  */
class RenameStage(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp(p))
    val dispatch = Input(Bool())
    val frm = Input(UInt(3.W))
    val renamed = Output(new IssueEntry(p))
    val lookupTags = Output(Vec(2, new RobTag(p)))
    val lookupValues = Input(Vec(2, new Operand(p)))
    val fLookupTags = Output(Vec(3, new RobTag(p)))
    val fLookupValues = Input(Vec(3, new Operand(p)))
    val vectorLookupTags = Output(Vec(4, new RobTag(p)))
    val vectorLookupValues = Input(Vec(4, new VectorOperand(p)))
    val wakeup = Input(Valid(new ExeResult(p)))
    val commit = Input(Valid(new CommitRecord(p)))
    val flush = Input(Bool())
    val debugRegs = Output(Vec(32, UInt(32.W)))
    val debugFRegs = Output(Vec(32, UInt(32.W)))
    val debugVRegs = Output(Vec(32, UInt(p.vlen.W)))
  })

  // x0 has neither architectural storage nor a RAT entry.
  val registers = RegInit(VecInit(Seq.fill(31)(0.U(32.W))))
  val mapped = RegInit(VecInit(Seq.fill(31)(false.B)))
  val mappings = Reg(Vec(31, new RobTag(p)))
  val commitWrites = io.commit.valid && !io.commit.bits.exception &&
    io.commit.bits.uop.writesRd && io.commit.bits.uop.rd =/= 0.U
  // f0 is an ordinary writable register with its own namespace.
  val fRegisters = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))
  val fMapped = RegInit(VecInit(Seq.fill(32)(false.B)))
  val fMappings = Reg(Vec(32, new RobTag(p)))
  val commitWritesF = io.commit.valid && !io.commit.bits.exception && io.commit.bits.uop.writesF
  // Every vector register, including v0, has architectural storage and a RAT entry.
  val vectorRegisters = RegInit(VecInit(Seq.fill(32)(0.U(p.vlen.W))))
  val vectorMapped = RegInit(VecInit(Seq.fill(32)(false.B)))
  val vectorMappings = Reg(Vec(32, new RobTag(p)))
  val commitWritesVector = io.commit.valid && !io.commit.bits.exception && io.commit.bits.uop.writesV
  io.debugVRegs := vectorRegisters

  io.debugRegs(0) := 0.U
  for (i <- 1 until 32) io.debugRegs(i) := registers(i - 1)
  io.debugFRegs := fRegisters
  io.renamed.uop := io.uop
  io.renamed.frm := io.frm

  val sourceRegisters = Seq(io.uop.rs1, io.uop.rs2)
  val sourceUsed = Seq(io.uop.usesRs1, io.uop.usesRs2)
  val operands = Seq(io.renamed.src1, io.renamed.src2)
  for (port <- 0 until 2) {
    val rs = sourceRegisters(port)
    val index = rs - 1.U
    val operand = operands(port)
    val commitSameRegister = commitWrites && io.commit.bits.uop.rd === rs
    operand := 0.U.asTypeOf(new Operand(p))
    operand.ready := true.B
    io.lookupTags(port) := 0.U.asTypeOf(new RobTag(p))
    when(sourceUsed(port) && rs =/= 0.U) {
      when(mapped(index)) {
        val tag = mappings(index)
        io.lookupTags(port) := tag
        operand.tag := tag
        operand.ready := false.B
        when(io.lookupValues(port).ready && TagMatch(io.lookupValues(port).tag, tag)) {
          operand.ready := true.B
          operand.value := io.lookupValues(port).value
        }
        when(io.wakeup.valid && !io.wakeup.bits.exception && TagMatch(io.wakeup.bits.tag, tag)) {
          operand.ready := true.B
          operand.value := io.wakeup.bits.data(31, 0)
        }
        when(commitSameRegister && TagMatch(io.commit.bits.uop.tag, tag)) {
          operand.ready := true.B
          operand.value := io.commit.bits.data(31, 0)
        }
      }.otherwise {
        operand.value := Mux(commitSameRegister, io.commit.bits.data(31, 0), registers(index))
      }
    }
  }

  val fSourceRegisters = Seq(io.uop.frs1, io.uop.frs2, io.uop.frs3)
  val fSourceUsed = Seq(io.uop.usesF1, io.uop.usesF2, io.uop.usesF3)
  val fOperands = Seq(io.renamed.fsrc1, io.renamed.fsrc2, io.renamed.fsrc3)
  for (port <- 0 until 3) {
    val rs = fSourceRegisters(port)
    val operand = fOperands(port)
    val commitSameRegister = commitWritesF && io.commit.bits.uop.rd === rs
    operand := 0.U.asTypeOf(new Operand(p))
    operand.ready := true.B
    io.fLookupTags(port) := 0.U.asTypeOf(new RobTag(p))
    when(fSourceUsed(port)) {
      when(fMapped(rs)) {
        val tag = fMappings(rs)
        io.fLookupTags(port) := tag
        operand.tag := tag
        operand.ready := false.B
        when(io.fLookupValues(port).ready && TagMatch(io.fLookupValues(port).tag, tag)) {
          operand.ready := true.B
          operand.value := io.fLookupValues(port).value
        }
        when(io.wakeup.valid && !io.wakeup.bits.exception && TagMatch(io.wakeup.bits.tag, tag)) {
          operand.ready := true.B
          operand.value := io.wakeup.bits.data(31, 0)
        }
        when(commitSameRegister && TagMatch(io.commit.bits.uop.tag, tag)) {
          operand.ready := true.B
          operand.value := io.commit.bits.data(31, 0)
        }
      }.otherwise {
        operand.value := Mux(commitSameRegister, io.commit.bits.data(31, 0), fRegisters(rs))
      }
    }
  }

  val vectorSources = Seq(io.uop.rs1, io.uop.rs2, io.uop.rd, io.uop.maskReg)
  val vectorUsed = Seq(io.uop.usesV1, io.uop.usesV2, io.uop.usesVOld, io.uop.usesVMask)
  val vectorOperands = Seq(io.renamed.vsrc1, io.renamed.vsrc2, io.renamed.vold, io.renamed.vmask)
  for (port <- 0 until 4) {
    val rs = vectorSources(port)
    val operand = vectorOperands(port)
    val commitSameRegister = commitWritesVector && io.commit.bits.uop.rd === rs
    operand := 0.U.asTypeOf(new VectorOperand(p))
    operand.ready := true.B
    io.vectorLookupTags(port) := 0.U.asTypeOf(new RobTag(p))
    when(vectorUsed(port)) {
      when(vectorMapped(rs)) {
        val tag = vectorMappings(rs)
        io.vectorLookupTags(port) := tag
        operand.tag := tag
        operand.ready := false.B
        when(io.vectorLookupValues(port).ready && TagMatch(io.vectorLookupValues(port).tag, tag)) {
          operand.ready := true.B
          operand.value := io.vectorLookupValues(port).value
        }
        when(io.wakeup.valid && !io.wakeup.bits.exception && TagMatch(io.wakeup.bits.tag, tag)) {
          operand.ready := true.B
          operand.value := io.wakeup.bits.data
        }
        when(commitSameRegister && TagMatch(io.commit.bits.uop.tag, tag)) {
          operand.ready := true.B
          operand.value := io.commit.bits.data
        }
      }.otherwise {
        operand.value := Mux(commitSameRegister, io.commit.bits.data, vectorRegisters(rs))
      }
    }
  }

  when(commitWrites) {
    val index = io.commit.bits.uop.rd - 1.U
    // Even a superseded writer updates architectural state for precise faults.
    registers(index) := io.commit.bits.data(31, 0)
    when(mapped(index) && TagMatch(mappings(index), io.commit.bits.uop.tag)) {
      mapped(index) := false.B
    }
  }
  when(commitWritesF) {
    val rd = io.commit.bits.uop.rd
    fRegisters(rd) := io.commit.bits.data(31, 0)
    when(fMapped(rd) && TagMatch(fMappings(rd), io.commit.bits.uop.tag)) {
      fMapped(rd) := false.B
    }
  }
  when(commitWritesVector) {
    val rd = io.commit.bits.uop.rd
    vectorRegisters(rd) := io.commit.bits.data
    when(vectorMapped(rd) && TagMatch(vectorMappings(rd), io.commit.bits.uop.tag)) {
      vectorMapped(rd) := false.B
    }
  }
  // Last-connect priority preserves a new writer over an older same-cycle commit.
  when(io.dispatch && io.uop.writesRd && io.uop.rd =/= 0.U && !io.uop.exception) {
    val index = io.uop.rd - 1.U
    mapped(index) := true.B
    mappings(index) := io.uop.tag
  }
  when(io.dispatch && io.uop.writesF && !io.uop.exception) {
    fMapped(io.uop.rd) := true.B
    fMappings(io.uop.rd) := io.uop.tag
  }
  when(io.dispatch && io.uop.writesV && !io.uop.exception) {
    vectorMapped(io.uop.rd) := true.B
    vectorMappings(io.uop.rd) := io.uop.tag
  }
  when(io.flush) {
    mapped.foreach(_ := false.B)
    fMapped.foreach(_ := false.B)
    vectorMapped.foreach(_ := false.B)
  }
}
