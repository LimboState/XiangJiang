package xiangjiang

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.decode.DecodeUnit
import xiangjiang.backend.fu._

/** Keeps the tests on real decode/datapath hardware while supplying captured operands. */
class ScalarExecutionHarness(p: XJParams, branch: Boolean) extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))
    val pc = Input(UInt(32.W))
    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W))
    val tag = Input(new RobTag(p))
    val seq = Input(UInt(p.seqBits.W))
    val valid = Input(Bool())
    val ready = Output(Bool())
    val flush = Input(Bool())
    val busy = Output(Bool())
    val decoded = Output(new MicroOp(p))
    val resp = Decoupled(new ExeResult(p))
  })
  val decode = Module(new DecodeUnit(p))
  decode.io.in := 0.U.asTypeOf(new FetchPacket)
  decode.io.in.inst := io.inst
  decode.io.in.pc := io.pc
  val unit: ScalarFunctionalUnit = if (branch) Module(new BranchUnit(p)) else Module(new ALUUnit(p))
  unit.io.req.bits := 0.U.asTypeOf(new IssueEntry(p))
  unit.io.req.bits.uop := decode.io.out
  unit.io.req.bits.uop.tag := io.tag
  unit.io.req.bits.uop.seq := io.seq
  unit.io.req.bits.src1.ready := true.B
  unit.io.req.bits.src1.value := io.a
  unit.io.req.bits.src2.ready := true.B
  unit.io.req.bits.src2.value := io.b
  unit.io.req.valid := io.valid
  unit.io.flush := io.flush
  io.ready := unit.io.req.ready
  io.busy := unit.io.busy
  io.resp <> unit.io.resp
  io.decoded := decode.io.out
}

class ScalarExecutionSpec extends AnyFunSuite with ChiselSim {
  private val p = XJParams()
  private val mask = BigInt("ffffffff", 16)
  private def word(x: BigInt): BigInt = x & mask
  private def r(f3: Int, f7: Int = 0, rd: Int = 3): BigInt =
    (BigInt(f7) << 25) | (2 << 20) | (1 << 15) | (f3 << 12) | (rd << 7) | 0x33
  private def i(imm: Int, f3: Int = 0, opcode: Int = 0x13, rd: Int = 3, rs1: Int = 1): BigInt =
    (BigInt(imm & 0xfff) << 20) | (rs1 << 15) | (f3 << 12) | (rd << 7) | opcode
  private def s(imm: Int, f3: Int = 2): BigInt =
    (BigInt((imm & 0xfff) >> 5) << 25) | (2 << 20) | (1 << 15) | (f3 << 12) | ((imm & 31) << 7) | 0x23
  private def b(imm: Int, f3: Int = 0): BigInt = {
    val x = imm & 0x1fff
    (BigInt((x >> 12) & 1) << 31) | (((x >> 5) & 63) << 25) | (2 << 20) |
      (1 << 15) | (f3 << 12) | (((x >> 1) & 15) << 8) | (((x >> 11) & 1) << 7) | 0x63
  }
  private def j(imm: Int, rd: Int = 3): BigInt = {
    val x = imm & 0x1fffff
    (BigInt((x >> 20) & 1) << 31) | (((x >> 1) & 1023) << 21) |
      (((x >> 11) & 1) << 20) | (((x >> 12) & 255) << 12) | (rd << 7) | 0x6f
  }
  private def u(imm: Int, opcode: Int): BigInt = (BigInt(imm & 0xfffff) << 12) | (3 << 7) | opcode

  test("decode the 33 supported scalar instructions plus custom HALT with exact metadata") {
    simulate(new DecodeUnit(p)) { c =>
      c.io.in.pc.poke(0x100)
      c.io.in.exception.poke(false)
      c.io.in.cause.poke(0)
      c.io.in.badAddr.poke(0)
      val rr = Seq((0, 0, UOpCode.ADD), (0, 32, UOpCode.SUB), (7, 0, UOpCode.AND),
        (6, 0, UOpCode.OR), (4, 0, UOpCode.XOR), (1, 0, UOpCode.SLL),
        (5, 0, UOpCode.SRL), (5, 32, UOpCode.SRA), (2, 0, UOpCode.SLT), (3, 0, UOpCode.SLTU))
      val ri = Seq((0, -1, UOpCode.ADDI), (7, -1, UOpCode.ANDI), (6, -1, UOpCode.ORI),
        (4, -1, UOpCode.XORI), (1, 31, UOpCode.SLLI), (5, 31, UOpCode.SRLI),
        (5, 0x41f, UOpCode.SRAI), (2, -1, UOpCode.SLTI), (3, -1, UOpCode.SLTIU))
      val branches = Seq((0, UOpCode.BEQ), (1, UOpCode.BNE), (4, UOpCode.BLT),
        (5, UOpCode.BGE), (6, UOpCode.BLTU), (7, UOpCode.BGEU))
      val cases = rr.map { case (f3, f7, op) => (r(f3, f7), op, FuType.alu, true, true, true, false, BigInt(0)) } ++
        ri.map { case (f3, imm, op) => (i(imm, f3), op, FuType.alu, true, false, true, true,
          if (f3 == 1 || f3 == 5) BigInt(31) else mask) } ++
        Seq((u(0xabcde, 0x37), UOpCode.LUI, FuType.alu, false, false, true, true, BigInt("abcde000", 16)),
          (u(0xabcde, 0x17), UOpCode.AUIPC, FuType.alu, false, false, true, true, BigInt("abcde000", 16))) ++
        Seq((i(-4, 2, 3), UOpCode.LW, FuType.mem, true, false, true, true, word(-4)),
          (s(-4, 2), UOpCode.SW, FuType.mem, true, true, false, true, word(-4))) ++
        branches.map { case (f3, op) => (b(-4, f3), op, FuType.bru, true, true, false, false, word(-4)) } ++
        Seq((j(-4), UOpCode.JAL, FuType.bru, false, false, true, true, word(-4)),
          (i(-4, 0, 0x67), UOpCode.JALR, FuType.bru, true, false, true, true, word(-4)),
          (BigInt("ffff8f8f", 16), UOpCode.FENCE, FuType.system, false, false, false, false, BigInt(0)),
          (Rv32.halt, UOpCode.HALT, FuType.system, false, false, false, false, BigInt(0)))
      assert(cases.size == 33)
      for ((inst, op, fu, rs1, rs2, rd, useImm, imm) <- cases) {
        c.io.in.inst.poke(inst)
        c.io.out.inst.expect(inst)
        c.io.out.pc.expect(0x100)
        c.io.out.op.expect(op)
        c.io.out.fu.expect(fu)
        c.io.out.exception.expect(false)
        c.io.out.cause.expect(0)
        c.io.out.badAddr.expect(0)
        c.io.out.usesRs1.expect(rs1)
        c.io.out.usesRs2.expect(rs2)
        c.io.out.writesRd.expect(rd)
        c.io.out.useImm.expect(useImm)
        c.io.out.imm.expect(imm)
        c.io.out.barrier.expect(fu == FuType.bru || fu == FuType.system)
        c.io.out.tag.slot.expect(0)
        c.io.out.tag.generation.expect(0)
        c.io.out.seq.expect(0)
        if (rd) {
          c.io.in.inst.poke(inst & ~(BigInt(31) << 7))
          c.io.out.writesRd.expect(false)
          c.io.out.exception.expect(false)
        }
      }
    }
  }

  test("decode exact immediate boundaries and exhaustive function field legality") {
    simulate(new DecodeUnit(p)) { c =>
      c.io.in.pc.poke(0)
      c.io.in.exception.poke(false)
      c.io.in.cause.poke(0)
      c.io.in.badAddr.poke(0)
      val immediates = Seq(-2048, -1, 0, 1, 2047).flatMap(x => Seq((i(x), word(x)), (s(x), word(x)))) ++
        Seq(-4096, -2050, -2, 0, 2, 2048, 4094).map(x => (b(x), word(x))) ++
        Seq(-1048576, -4098, -2, 0, 2, 2048, 4096, 1048574).map(x => (j(x), word(x))) ++
        Seq((u(0x80000, 0x37), BigInt("80000000", 16)), (u(0xfffff, 0x17), BigInt("fffff000", 16)))
      for ((inst, imm) <- immediates) {
        c.io.in.inst.poke(inst)
        c.io.out.exception.expect(false)
        c.io.out.imm.expect(imm)
      }
      for (f7 <- 0 until 128; f3 <- 0 until 8) {
        val legalR = f7 == 0 || (f7 == 32 && (f3 == 0 || f3 == 5))
        c.io.in.inst.poke(r(f3, f7))
        c.io.out.exception.expect(!legalR)
        if (!legalR) { c.io.out.writesRd.expect(false); c.io.out.badAddr.expect(r(f3, f7)) }
        val legalI = (f3 != 1 && f3 != 5) || f7 == 0 || (f3 == 5 && f7 == 32)
        c.io.in.inst.poke(i((f7 << 5) | 31, f3))
        c.io.out.exception.expect(!legalI)
      }
      for (f3 <- 0 until 8; opcode <- Seq(3, 0x23, 0x63, 0x67, 0x0f)) {
        val legal = opcode match {
          case 3 => f3 == 2
          case 0x23 => f3 == 2
          case 0x63 => f3 != 2 && f3 != 3
          case _ => f3 == 0
        }
        c.io.in.inst.poke(i(0, f3, opcode))
        c.io.out.exception.expect(!legal)
      }
      // There is no privileged or environment-call subset: every SYSTEM word is illegal.
      val systems = (0 until 4096).map(imm => i(imm, 0, 0x73, rd = 0, rs1 = 0)) ++
        (for (imm <- Seq(0, 1); f3 <- 0 until 8; rd <- Seq(0, 1, 31); rs1 <- Seq(0, 1, 31))
          yield i(imm, f3, 0x73, rd, rs1))
      for (inst <- systems) {
        c.io.in.inst.poke(inst)
        c.io.out.exception.expect(true)
        c.io.out.cause.expect(1)
        c.io.out.badAddr.expect(inst)
        c.io.out.writesRd.expect(false)
        c.io.out.usesRs1.expect(false); c.io.out.usesRs2.expect(false)
        c.io.out.barrier.expect(true)
      }
      // Byte/halfword accesses, other custom-0 words and the all-zero word are illegal.
      val illegal = Seq(BigInt(0), BigInt(1), BigInt(2), BigInt(4),
        i(0, 0, 3, rd = 20, rs1 = 1), i(0, 1, 3, rd = 20, rs1 = 1),
        i(0, 4, 3, rd = 20, rs1 = 1), i(0, 5, 3, rd = 20, rs1 = 1),
        s(0, 0), s(0, 1), BigInt(0x200073),
        BigInt(0xf3), BigInt(0x8073), BigInt(0x1000f3), BigInt(0x108073),
        BigInt(0x1073), BigInt(0x100f), BigInt(0x53),
        Rv32.halt | 0x80, Rv32.halt | (BigInt(1) << 26), Rv32.halt | (BigInt(1) << 12), mask)
      for (inst <- illegal) {
        c.io.in.inst.poke(inst)
        c.io.out.exception.expect(true)
        c.io.out.cause.expect(1)
        c.io.out.badAddr.expect(inst)
        c.io.out.barrier.expect(true)
        c.io.out.writesRd.expect(false)
      }
      // An access failure wins over HALT and any illegal machine word.
      c.io.in.exception.poke(true)
      c.io.in.cause.poke(3)
      c.io.in.badAddr.poke(0x80000)
      for (inst <- Seq(BigInt(0), Rv32.halt, mask, i(1))) {
        c.io.in.inst.poke(inst)
        c.io.out.exception.expect(true)
        c.io.out.cause.expect(3)
        c.io.out.badAddr.expect(0x80000)
        c.io.out.writesRd.expect(false)
        c.io.out.usesRs1.expect(false)
        c.io.out.usesRs2.expect(false)
      }
    }
  }

  private def initialize(c: ScalarExecutionHarness): Unit = {
    c.io.inst.poke(i(0))
    c.io.pc.poke(0x100)
    c.io.a.poke(0)
    c.io.b.poke(0)
    c.io.tag.slot.poke(2)
    c.io.tag.generation.poke(3)
    c.io.seq.poke(7)
    c.io.valid.poke(false)
    c.io.flush.poke(false)
    c.io.resp.ready.poke(true)
  }

  test("ALU executes every operation with wrap signed comparisons and five bit shifts") {
    simulate(new ScalarExecutionHarness(p, false)) { c =>
      initialize(c)
      val cases = Seq(
        (r(0), mask, BigInt(1), BigInt(0)), (r(0, 32), BigInt(0), BigInt(1), mask),
        (r(7), BigInt("f0f0f0f0", 16), BigInt("aa55aa55", 16), BigInt("a050a050", 16)),
        (r(6), BigInt(0xf0), BigInt(0x0f), BigInt(0xff)),
        (r(4), BigInt(0xff), BigInt(0x55), BigInt(0xaa)),
        (r(1), BigInt(1), BigInt(63), BigInt("80000000", 16)),
        (r(5), BigInt("80000000", 16), BigInt(63), BigInt(1)),
        (r(5, 32), BigInt("80000000", 16), BigInt(63), mask),
        (r(2), BigInt("80000000", 16), BigInt(0), BigInt(1)),
        (r(3), mask, BigInt(0), BigInt(0)),
        (i(1), BigInt("7fffffff", 16), BigInt(0), BigInt("80000000", 16)),
        (i(-1, 7), BigInt("12345678", 16), BigInt(0), BigInt("12345678", 16)),
        (i(-2048, 6), BigInt(0), BigInt(0), BigInt("fffff800", 16)),
        (i(-1, 4), BigInt("12345678", 16), BigInt(0), BigInt("edcba987", 16)),
        (i(31, 1), BigInt(1), BigInt(0), BigInt("80000000", 16)),
        (i(31, 5), mask, BigInt(0), BigInt(1)),
        (i(0x41f, 5), BigInt("80000000", 16), BigInt(0), mask),
        (i(-1, 2), BigInt("80000000", 16), BigInt(0), BigInt(1)),
        (i(-1, 3), BigInt("fffffffe", 16), BigInt(0), BigInt(1)),
        (u(0xabcde, 0x37), BigInt(123), BigInt(456), BigInt("abcde000", 16)),
        (u(0xfffff, 0x17), BigInt(123), BigInt(456), BigInt("fffff100", 16)))
      for ((inst, a, b, expected) <- cases) {
        c.io.inst.poke(inst); c.io.a.poke(a); c.io.b.poke(b)
        c.io.valid.poke(true)
        c.io.ready.expect(true)
        c.clock.step()
        c.io.resp.valid.expect(true)
        c.io.resp.bits.data.expect(expected)
        c.io.resp.bits.nextPc.expect(0x104)
        c.io.resp.bits.exception.expect(false)
        c.io.resp.bits.cause.expect(0)
        c.io.resp.bits.tag.slot.expect(2)
        c.io.resp.bits.tag.generation.expect(3)
        c.io.resp.bits.seq.expect(7)
      }
      // Shift-by-32 is shift-by-zero; equality must not satisfy either SLT.
      for (inst <- Seq(r(1), r(5), r(5, 32))) {
        c.io.inst.poke(inst); c.io.a.poke(mask); c.io.b.poke(32)
        c.clock.step(); c.io.resp.bits.data.expect(mask)
      }
      for (inst <- Seq(r(2), r(3))) {
        c.io.inst.poke(inst); c.io.a.poke(mask); c.io.b.poke(mask)
        c.clock.step(); c.io.resp.bits.data.expect(0)
      }
      c.io.valid.poke(false)
      c.clock.step()
      c.io.resp.valid.expect(false)
      c.io.busy.expect(false)
    }
  }

  test("branches select only the real next PC and preserve old JALR base when rd equals rs1") {
    simulate(new ScalarExecutionHarness(p, true)) { c =>
      initialize(c)
      def run(inst: BigInt, a: BigInt, b: BigInt, pc: BigInt, next: BigInt, cause: Int = 0, link: BigInt = 0): Unit = {
        c.io.inst.poke(inst); c.io.a.poke(a); c.io.b.poke(b); c.io.pc.poke(pc)
        c.io.valid.poke(true); c.io.ready.expect(true)
        c.clock.step()
        c.io.resp.valid.expect(true)
        c.io.resp.bits.nextPc.expect(next)
        c.io.resp.bits.exception.expect(cause != 0)
        c.io.resp.bits.cause.expect(cause)
        c.io.resp.bits.badAddr.expect(if (cause == 0) BigInt(0) else next)
        c.io.resp.bits.data.expect(link)
      }
      for ((f3, a, bval, taken) <- Seq(
        (0, BigInt(7), BigInt(7), true), (1, BigInt(7), BigInt(7), false),
        (4, mask, BigInt(0), true), (5, mask, BigInt(0), false),
        (6, mask, BigInt(0), false), (7, mask, BigInt(0), true))) {
        run(b(8, f3), a, bval, 0x100, if (taken) 0x108 else 0x104)
        run(b(8, f3), bval, a, 0x100, if (a == bval) { if (taken) 0x108 else 0x104 } else { if (taken) 0x104 else 0x108 })
      }
      for (f3 <- Seq(4, 6)) run(b(8, f3), mask, mask, 0x100, 0x104)
      for (f3 <- Seq(5, 7)) run(b(8, f3), mask, mask, 0x100, 0x108)
      run(b(2), 1, 0, 0x100, 0x104) // Unselected misaligned target is harmless.
      run(b(-4096), 1, 0, 0x100, 0x104) // Unselected out-of-range target is harmless.
      run(b(2), 1, 1, 0x100, 0x102, 2)
      run(b(8), 1, 1, 0x7fffc, 0x80004, 3)
      run(b(-4), 1, 0, 0x7fffc, 0x80000, 3) // Not taken fallthrough is still checked.
      run(j(-4), 0, 0, 4, 0, link = 8)
      run(j(4, rd = 0), 0, 0, 0x7fff8, 0x7fffc, link = 0x7fffc)
      c.io.decoded.writesRd.expect(false)
      run(j(2), 0, 0, 0x100, 0x102, 2)
      run(j(4), 0, 0, 0x7fffc, 0x80000, 3)
      run(j(-4), 0, 0, 0, mask - 3, 3)
      run(i(4, 0, 0x67, rd = 1, rs1 = 1), 0x200, 0, 0x100, 0x204, link = 0x104)
      run(i(0, 0, 0x67), 0x201, 0, 0x100, 0x200, link = 0x104)
      run(i(0, 0, 0x67), 0x203, 0, 0x100, 0x202, 2)
      run(i(0, 0, 0x67), 0x80003, 0, 0x100, 0x80002, 2)
      run(i(1, 0, 0x67), mask, 0, 0x100, 0, link = 0x104)
      run(b(8, 0), 1, 0, 0x100, 0x104)
      run(b(8, 1), 1, 0, 0x100, 0x108)
      // Faults, including rd=x0 jumps, must also remain buffered until accepted.
      run(j(2, rd = 0), 0, 0, 0x100, 0x102, 2)
      c.io.resp.ready.poke(false)
      c.io.inst.poke(j(8)); c.io.pc.poke(0x200)
      for (_ <- 0 until 4) {
        c.io.ready.expect(false)
        c.io.resp.valid.expect(true)
        c.io.resp.bits.exception.expect(true)
        c.io.resp.bits.cause.expect(2)
        c.io.resp.bits.badAddr.expect(0x102)
        c.io.resp.bits.nextPc.expect(0x102)
        c.io.resp.bits.data.expect(0)
        c.clock.step()
      }
      c.io.resp.ready.poke(true)
      c.io.valid.poke(false); c.clock.step(); c.io.busy.expect(false)
    }
  }

  for (branch <- Seq(false, true)) {
    test(s"${if (branch) "branch" else "ALU"} holds one completion under backpressure replaces it at II1 and flushes") {
      simulate(new ScalarExecutionHarness(p, branch)) { c =>
        initialize(c)
        c.io.inst.poke(if (branch) j(8, rd = 0) else i(5, rd = 0))
        c.io.a.poke(7)
        c.io.valid.poke(true)
        c.io.resp.ready.poke(false)
        c.io.decoded.writesRd.expect(false)
        c.io.resp.valid.expect(false)
        c.io.ready.expect(true)
        c.clock.step()
        val expectedData = if (branch) 0x104 else 12
        val expectedNext = if (branch) 0x108 else 0x104
        c.io.busy.expect(true)
        c.io.inst.poke(if (branch) j(16) else i(9))
        c.io.a.poke(20); c.io.pc.poke(0x200)
        c.io.tag.slot.poke(1); c.io.tag.generation.poke(4); c.io.seq.poke(8)
        for (_ <- 0 until 5) {
          c.io.ready.expect(false)
          c.io.resp.valid.expect(true)
          c.io.resp.bits.data.expect(expectedData)
          c.io.resp.bits.nextPc.expect(expectedNext)
          c.io.resp.bits.tag.slot.expect(2)
          c.io.resp.bits.tag.generation.expect(3)
          c.io.resp.bits.seq.expect(7)
          c.io.resp.bits.exception.expect(false)
          c.io.resp.bits.cause.expect(0)
          c.io.resp.bits.badAddr.expect(0)
          c.clock.step()
        }
        c.io.resp.ready.poke(true)
        c.io.ready.expect(true)
        c.clock.step()
        c.io.resp.valid.expect(true)
        c.io.resp.bits.data.expect(if (branch) 0x204 else 29)
        c.io.resp.bits.nextPc.expect(if (branch) 0x210 else 0x204)
        c.io.resp.bits.tag.slot.expect(1)
        c.io.resp.bits.tag.generation.expect(4)
        c.io.resp.bits.seq.expect(8)
        c.io.flush.poke(true)
        c.io.ready.expect(false)
        c.io.resp.valid.expect(false)
        c.clock.step()
        c.io.flush.poke(false)
        c.io.valid.poke(false)
        c.io.busy.expect(false)
        c.io.resp.valid.expect(false)
        c.clock.step(2)
        c.io.resp.valid.expect(false)
      }
    }
  }
}
