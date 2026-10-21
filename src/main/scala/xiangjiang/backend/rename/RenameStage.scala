package xiangjiang.backend.rename

import chisel3._
import chisel3.util._
import xiangjiang._

/** Captures source versions before installing the current destination mapping. */
class RenameStage(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val uop = Input(new MicroOp(p))
    val dispatch = Input(Bool())
    val renamed = Output(new IssueEntry(p))
    val lookupTags = Output(Vec(2, new RobTag(p)))
    val lookupValues = Input(Vec(2, new Operand(p)))
    val wakeup = Input(Valid(new ExeResult(p)))
    val commit = Input(Valid(new CommitRecord(p)))
    val flush = Input(Bool())
    val debugRegs = Output(Vec(32, UInt(32.W)))
  })

  // x0 has neither architectural storage nor a RAT entry.
  val registers = RegInit(VecInit(Seq.fill(31)(0.U(32.W))))
  val mapped = RegInit(VecInit(Seq.fill(31)(false.B)))
  val mappings = Reg(Vec(31, new RobTag(p)))
  val commitWrites = io.commit.valid && !io.commit.bits.exception &&
    io.commit.bits.uop.writesRd && io.commit.bits.uop.rd =/= 0.U

  io.debugRegs(0) := 0.U
  for (i <- 1 until 32) io.debugRegs(i) := registers(i - 1)
  io.renamed.uop := io.uop

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
          operand.value := io.wakeup.bits.data
        }
        when(commitSameRegister && TagMatch(io.commit.bits.uop.tag, tag)) {
          operand.ready := true.B
          operand.value := io.commit.bits.data
        }
      }.otherwise {
        operand.value := Mux(commitSameRegister, io.commit.bits.data, registers(index))
      }
    }
  }

  when(commitWrites) {
    val index = io.commit.bits.uop.rd - 1.U
    // Even a superseded writer updates architectural state for precise faults.
    registers(index) := io.commit.bits.data
    when(mapped(index) && TagMatch(mappings(index), io.commit.bits.uop.tag)) {
      mapped(index) := false.B
    }
  }
  // Last-connect priority preserves a new writer over an older same-cycle commit.
  when(io.dispatch && io.uop.writesRd && io.uop.rd =/= 0.U && !io.uop.exception) {
    val index = io.uop.rd - 1.U
    mapped(index) := true.B
    mappings(index) := io.uop.tag
  }
  when(io.flush) {
    mapped.foreach(_ := false.B)
  }
}
