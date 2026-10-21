package xiangjiang.backend.decode

import chisel3._
import chisel3.util._
import xiangjiang._

/** Strict RV32 scalar-subset decode; exception records still enter the ROB. */
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
  when(opcode === "h03".U && funct3 === 2.U) { decode(UOpCode.LW, FuType.mem, true, false, true, immI, true) }
  when(opcode === "h23".U && funct3 === 2.U) { decode(UOpCode.SW, FuType.mem, true, true, false, immS, true) }
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
  when(inst === 0.U) { decode(UOpCode.HALT, FuType.system, false, false, false, 0.U, false) }

  io.out.exception := io.in.exception || !legal
  io.out.cause := Mux(io.in.exception, io.in.cause, Mux(!legal, FaultCode.illegal, FaultCode.none))
  io.out.badAddr := Mux(io.in.exception, io.in.badAddr, Mux(!legal, inst, 0.U))
  io.out.writesRd := destination && io.out.rd =/= 0.U && !io.out.exception
  io.out.barrier := io.out.fu === FuType.bru || io.out.fu === FuType.system || io.out.exception
  when(io.out.exception) {
    io.out.usesRs1 := false.B
    io.out.usesRs2 := false.B
  }
}
