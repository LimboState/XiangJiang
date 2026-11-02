package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import top.XiangJiangNPU

/** Encoders are test-side RV32 field assembly, independent of the RTL decoder. */
object Rv32 {
  def i(op: Int, rd: Int, f3: Int, rs1: Int, imm: Int): BigInt =
    (BigInt(imm & 0xfff) << 20) | (rs1 << 15) | (f3 << 12) | (rd << 7) | op
  def r(rd: Int, f3: Int, rs1: Int, rs2: Int, f7: Int = 0): BigInt =
    (BigInt(f7) << 25) | (rs2 << 20) | (rs1 << 15) | (f3 << 12) | (rd << 7) | 0x33
  def addi(rd: Int, rs1: Int, imm: Int): BigInt = i(0x13, rd, 0, rs1, imm)
  def lui(rd: Int, imm: Int): BigInt = (BigInt(imm & 0xfffff) << 12) | (rd << 7) | 0x37
  def lb(rd: Int, rs1: Int, imm: Int): BigInt = i(3, rd, 0, rs1, imm)
  def lh(rd: Int, rs1: Int, imm: Int): BigInt = i(3, rd, 1, rs1, imm)
  def lbu(rd: Int, rs1: Int, imm: Int): BigInt = i(3, rd, 4, rs1, imm)
  def lhu(rd: Int, rs1: Int, imm: Int): BigInt = i(3, rd, 5, rs1, imm)
  def lw(rd: Int, rs1: Int, imm: Int): BigInt = i(3, rd, 2, rs1, imm)
  private def store(f3: Int, rs2: Int, rs1: Int, imm: Int): BigInt =
    (BigInt((imm >> 5) & 0x7f) << 25) | (rs2 << 20) | (rs1 << 15) | (f3 << 12) | ((imm & 31) << 7) | 0x23
  def sb(rs2: Int, rs1: Int, imm: Int): BigInt = store(0, rs2, rs1, imm)
  def sh(rs2: Int, rs1: Int, imm: Int): BigInt = store(1, rs2, rs1, imm)
  def sw(rs2: Int, rs1: Int, imm: Int): BigInt = store(2, rs2, rs1, imm)
  def b(f3: Int, rs1: Int, rs2: Int, imm: Int): BigInt =
    (BigInt((imm >> 12) & 1) << 31) | (((imm >> 5) & 63) << 25) | (rs2 << 20) |
      (rs1 << 15) | (f3 << 12) | (((imm >> 1) & 15) << 8) | (((imm >> 11) & 1) << 7) | 0x63
  def jal(rd: Int, imm: Int): BigInt =
    (BigInt((imm >> 20) & 1) << 31) | (((imm >> 1) & 1023) << 21) |
      (((imm >> 11) & 1) << 20) | (((imm >> 12) & 255) << 12) | (rd << 7) | 0x6f
  val fence: BigInt = BigInt("0ff0000f", 16)
  val fencei: BigInt = BigInt("0000100f", 16)
  val ecall: BigInt = BigInt("00000073", 16)
  val ebreak: BigInt = BigInt("00100073", 16)
  val halt: BigInt = BigInt("0000000b", 16)
  def csrrw(rd: Int, csr: Int, rs1: Int): BigInt =
    (BigInt(csr & 0xfff) << 20) | (rs1 << 15) | (1 << 12) | (rd << 7) | 0x73
  def csrrs(rd: Int, csr: Int, rs1: Int): BigInt =
    (BigInt(csr & 0xfff) << 20) | (rs1 << 15) | (2 << 12) | (rd << 7) | 0x73
  def csrrc(rd: Int, csr: Int, rs1: Int): BigInt =
    (BigInt(csr & 0xfff) << 20) | (rs1 << 15) | (3 << 12) | (rd << 7) | 0x73
  def csrrwi(rd: Int, csr: Int, uimm: Int): BigInt =
    (BigInt(csr & 0xfff) << 20) | (uimm << 15) | (5 << 12) | (rd << 7) | 0x73
  def csrrsi(rd: Int, csr: Int, uimm: Int): BigInt =
    (BigInt(csr & 0xfff) << 20) | (uimm << 15) | (6 << 12) | (rd << 7) | 0x73
  def csrrci(rd: Int, csr: Int, uimm: Int): BigInt =
    (BigInt(csr & 0xfff) << 20) | (uimm << 15) | (7 << 12) | (rd << 7) | 0x73
  def mul(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 0, rs1, rs2, 1)
  def mulh(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 1, rs1, rs2, 1)
  def mulhsu(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 2, rs1, rs2, 1)
  def mulhu(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 3, rs1, rs2, 1)
  def div(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 4, rs1, rs2, 1)
  def divu(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 5, rs1, rs2, 1)
  def rem(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 6, rs1, rs2, 1)
  def remu(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 7, rs1, rs2, 1)
  def clz(rd: Int, rs1: Int): BigInt = i(0x13, rd, 1, rs1, 0x600)
  def ctz(rd: Int, rs1: Int): BigInt = i(0x13, rd, 1, rs1, 0x601)
  def cpop(rd: Int, rs1: Int): BigInt = i(0x13, rd, 1, rs1, 0x602)
  def sextb(rd: Int, rs1: Int): BigInt = i(0x13, rd, 1, rs1, 0x604)
  def sexth(rd: Int, rs1: Int): BigInt = i(0x13, rd, 1, rs1, 0x605)
  def andn(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 7, rs1, rs2, 0x20)
  def orn(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 6, rs1, rs2, 0x20)
  def xnor(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 4, rs1, rs2, 0x20)
  def min(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 4, rs1, rs2, 0x05)
  def minu(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 5, rs1, rs2, 0x05)
  def max(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 6, rs1, rs2, 0x05)
  def maxu(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 7, rs1, rs2, 0x05)
  def rol(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 1, rs1, rs2, 0x30)
  def ror(rd: Int, rs1: Int, rs2: Int): BigInt = r(rd, 5, rs1, rs2, 0x30)
  def rori(rd: Int, rs1: Int, shamt: Int): BigInt = i(0x13, rd, 5, rs1, 0x600 | (shamt & 31))
  def rev8(rd: Int, rs1: Int): BigInt = i(0x13, rd, 5, rs1, 0x698)
  def orcb(rd: Int, rs1: Int): BigInt = i(0x13, rd, 5, rs1, 0x287)
  def zexth(rd: Int, rs1: Int): BigInt = r(rd, 4, rs1, 0, 0x04)
  // F extension selection.
  def flw(frd: Int, rs1: Int, imm: Int): BigInt = i(0x07, frd, 2, rs1, imm)
  def fsw(frs2: Int, rs1: Int, imm: Int): BigInt =
    (BigInt((imm >> 5) & 0x7f) << 25) | (frs2 << 20) | (rs1 << 15) | (2 << 12) | ((imm & 31) << 7) | 0x27
  def fadds(frd: Int, frs1: Int, frs2: Int, rm: Int = 0): BigInt =
    (BigInt(0x00) << 25) | (frs2 << 20) | (frs1 << 15) | (rm << 12) | (frd << 7) | 0x53
  def fsubs(frd: Int, frs1: Int, frs2: Int, rm: Int = 0): BigInt =
    (BigInt(0x04) << 25) | (frs2 << 20) | (frs1 << 15) | (rm << 12) | (frd << 7) | 0x53
  def fmuls(frd: Int, frs1: Int, frs2: Int, rm: Int = 0): BigInt =
    (BigInt(0x08) << 25) | (frs2 << 20) | (frs1 << 15) | (rm << 12) | (frd << 7) | 0x53
  def fdivs(frd: Int, frs1: Int, frs2: Int, rm: Int = 0): BigInt =
    (BigInt(0x0c) << 25) | (frs2 << 20) | (frs1 << 15) | (rm << 12) | (frd << 7) | 0x53
  def fsqrts(frd: Int, frs1: Int, rm: Int = 0): BigInt =
    (BigInt(0x2c) << 25) | (frs1 << 15) | (rm << 12) | (frd << 7) | 0x53
  def feqs(rd: Int, frs1: Int, frs2: Int): BigInt =
    (BigInt(0x50) << 25) | (frs2 << 20) | (frs1 << 15) | (2 << 12) | (rd << 7) | 0x53
  def flts(rd: Int, frs1: Int, frs2: Int): BigInt =
    (BigInt(0x50) << 25) | (frs2 << 20) | (frs1 << 15) | (1 << 12) | (rd << 7) | 0x53
  def fles(rd: Int, frs1: Int, frs2: Int): BigInt =
    (BigInt(0x50) << 25) | (frs2 << 20) | (frs1 << 15) | (0 << 12) | (rd << 7) | 0x53
  def fcvtsw(rd: Int, rs1: Int, rm: Int = 0): BigInt =
    (BigInt(0x68) << 25) | (rs1 << 15) | (rm << 12) | (rd << 7) | 0x53
  def fcvtswu(rd: Int, rs1: Int, rm: Int = 0): BigInt =
    (BigInt(0x68) << 25) | (1 << 20) | (rs1 << 15) | (rm << 12) | (rd << 7) | 0x53
  def fcvtws(rd: Int, frs1: Int, rm: Int = 0): BigInt =
    (BigInt(0x60) << 25) | (frs1 << 15) | (rm << 12) | (rd << 7) | 0x53
  def fcvtwus(rd: Int, frs1: Int, rm: Int = 0): BigInt =
    (BigInt(0x60) << 25) | (1 << 20) | (frs1 << 15) | (rm << 12) | (rd << 7) | 0x53
  def fmvxw(rd: Int, frs1: Int): BigInt =
    (BigInt(0x70) << 25) | (frs1 << 15) | (rd << 7) | 0x53
  def fmvwx(frd: Int, rs1: Int): BigInt =
    (BigInt(0x78) << 25) | (rs1 << 15) | (frd << 7) | 0x53
  def fmadds(frd: Int, frs1: Int, frs2: Int, frs3: Int, rm: Int = 0): BigInt =
    (frs3 << 27) | (frs2 << 20) | (frs1 << 15) | (rm << 12) | (frd << 7) | 0x43
  def fmsubs(frd: Int, frs1: Int, frs2: Int, frs3: Int, rm: Int = 0): BigInt =
    (frs3 << 27) | (frs2 << 20) | (frs1 << 15) | (rm << 12) | (frd << 7) | 0x47
  def fnmsubs(frd: Int, frs1: Int, frs2: Int, frs3: Int, rm: Int = 0): BigInt =
    (frs3 << 27) | (frs2 << 20) | (frs1 << 15) | (rm << 12) | (frd << 7) | 0x4b
  def fnmadds(frd: Int, frs1: Int, frs2: Int, frs3: Int, rm: Int = 0): BigInt =
    (frs3 << 27) | (frs2 << 20) | (frs1 << 15) | (rm << 12) | (frd << 7) | 0x4f
  // vset* configuration (RISC-V V encodings).
  def vsetvli(rd: Int, rs1: Int, vtypei: Int): BigInt =
    (BigInt(vtypei & 0x7ff) << 20) | (rs1 << 15) | (7 << 12) | (rd << 7) | 0x57
  def vsetvl(rd: Int, rs1: Int, rs2: Int): BigInt =
    (BigInt(1) << 31) | (rs2 << 20) | (rs1 << 15) | (7 << 12) | (rd << 7) | 0x57
  def vsetivli(rd: Int, uimm: Int, vtypei: Int): BigInt =
    (BigInt(3) << 30) | (BigInt(vtypei & 0x3ff) << 20) | (uimm << 15) |
      (7 << 12) | (rd << 7) | 0x57
  // vtype helpers: sew (0=8, 1=16, 2=32), lmul (0=1, 5=1/8, 6=1/4, 7=1/2, 1=2, 2=4, 3=8)
  def vtype(sew: Int, lmul: Int, vta: Int = 0, vma: Int = 0): Int =
    (vta << 6) | (vma << 7) | ((sew & 7) << 3) | (lmul & 7)
}

class XiangJiangNPUSpec extends AnyFunSuite with ChiselSim {
  test("executes host-loaded programs with ordered commits, barriers, precise faults and restart") {
    val p = XJParams(nRob = 4, nIssue = 1, nXRes = 1, nMemq = 1,
      generationBits = 1, seqBits = 4, bankBytes = 128)
    simulate(new XiangJiangNPU(p)) { dut =>
      import Rv32._
      val retired = scala.collection.mutable.ArrayBuffer.empty[(Long, Int, BigInt)]
      def tick(): Unit = {
        if (dut.io.retire.valid.peek().litToBoolean &&
          !dut.io.retire.bits.exception.peek().litToBoolean) {
          val c = dut.io.retire.bits
          retired += ((c.uop.pc.peek().litValue.toLong, c.uop.rd.peek().litValue.toInt, c.data.peek().litValue))
        }
        dut.clock.step()
      }
      def until(condition: => Boolean, limit: Int = 10000): Unit = {
        var cycles = 0
        while (!condition && cycles < limit) { tick(); cycles += 1 }
        assert(condition, s"timeout after $cycles cycles; retired=$retired")
      }
      val m = dut.io.memory
      val c = dut.io.control
      m.aw.valid.poke(false); m.w.valid.poke(false); m.ar.valid.poke(false)
      m.b.ready.poke(false); m.r.ready.poke(false)
      c.aw.valid.poke(false); c.w.valid.poke(false); c.ar.valid.poke(false)
      c.b.ready.poke(false); c.r.ready.poke(false)
      for (a <- Seq(m.aw.bits, m.ar.bits)) {
        a.id.poke(3); a.addr.poke(0); a.len.poke(0); a.size.poke(2); a.burst.poke(1)
        a.lock.poke(0); a.cache.poke(0); a.prot.poke(0); a.qos.poke(0)
      }
      m.w.bits.data.poke(0); m.w.bits.strb.poke(15); m.w.bits.last.poke(true)
      c.aw.bits.addr.poke(0); c.aw.bits.prot.poke(0)
      c.ar.bits.addr.poke(0); c.ar.bits.prot.poke(0)
      c.w.bits.data.poke(0); c.w.bits.strb.poke(15)
      tick()

      def writeMemory(addr: Long, words: Seq[BigInt]): Unit = {
        require(words.nonEmpty && words.size <= 256)
        m.aw.bits.addr.poke(addr); m.aw.bits.len.poke(words.size - 1)
        m.aw.valid.poke(true)
        until(m.aw.ready.peek().litToBoolean); tick(); m.aw.valid.poke(false)
        for ((word, index) <- words.zipWithIndex) {
          m.w.bits.data.poke(word & BigInt("ffffffff", 16))
          m.w.bits.last.poke(index == words.size - 1); m.w.valid.poke(true)
          until(m.w.ready.peek().litToBoolean); tick(); m.w.valid.poke(false)
        }
        until(m.b.valid.peek().litToBoolean)
        m.b.bits.resp.expect(0); m.b.bits.id.expect(3)
        // Completion must stay stable under host backpressure.
        tick(); tick(); m.b.valid.expect(true); m.b.bits.resp.expect(0)
        m.b.ready.poke(true); tick(); m.b.ready.poke(false)
      }
      def readMemory(addr: Long): BigInt = {
        m.ar.bits.addr.poke(addr); m.ar.bits.len.poke(0); m.ar.valid.poke(true)
        until(m.ar.ready.peek().litToBoolean); tick(); m.ar.valid.poke(false)
        until(m.r.valid.peek().litToBoolean)
        m.r.bits.resp.expect(0); m.r.bits.id.expect(3); m.r.bits.last.expect(true)
        val value = m.r.bits.data.peek().litValue
        tick(); m.r.bits.data.expect(value)
        m.r.ready.poke(true); tick(); m.r.ready.poke(false)
        value
      }
      def control(value: Int, probePendingStart: Boolean = false): Unit = {
        // Exercise W-before-AW acceptance on every command.
        c.w.bits.data.poke(value); c.w.valid.poke(true)
        until(c.w.ready.peek().litToBoolean); tick(); c.w.valid.poke(false)
        c.aw.valid.poke(true)
        until(c.aw.ready.peek().litToBoolean); tick(); c.aw.valid.poke(false)
        until(c.b.valid.peek().litToBoolean); c.b.bits.expect(0)
        if (probePendingStart) {
          // The Start write is accepted but the core start pulse is registered.
          // Busy/TCM ownership must already cover this cycle.
          assert(dut.io.status.busy.peek().litToBoolean)
          m.ar.bits.addr.poke(0x100000L); m.ar.bits.len.poke(0)
          m.ar.valid.poke(true)
          assert(m.ar.ready.peek().litToBoolean)
        }
        c.b.ready.poke(true); tick(); c.b.ready.poke(false)
        if (probePendingStart) {
          m.ar.valid.poke(false)
          until(m.r.valid.peek().litToBoolean)
          assert(m.r.bits.resp.peek().litValue == 2, "Host read raced pending Start")
          m.r.ready.poke(true); tick(); m.r.ready.poke(false)
        }
      }
      def readCsr(addr: Int): BigInt = {
        c.ar.bits.addr.poke(addr); c.ar.valid.poke(true)
        until(c.ar.ready.peek().litToBoolean); tick(); c.ar.valid.poke(false)
        until(c.r.valid.peek().litToBoolean); c.r.bits.resp.expect(0)
        val value = c.r.bits.data.peek().litValue
        c.r.ready.poke(true); tick(); c.r.ready.poke(false)
        value
      }
      def run(words: Seq[BigInt], expectedFault: Int = 0, faultPc: Int = 0,
          faultAddr: Option[BigInt] = None): Unit = {
        if (dut.io.status.done.peek().litToBoolean || dut.io.status.fault.peek().litToBoolean) {
          control(2)
          dut.io.status.done.expect(false); dut.io.status.fault.expect(false)
        }
        writeMemory(0, words)
        retired.clear()
        control(1)
        until(!dut.io.status.busy.peek().litToBoolean &&
          (dut.io.status.done.peek().litToBoolean || dut.io.status.fault.peek().litToBoolean))
        if (expectedFault == 0) {
          dut.io.status.done.expect(true); dut.io.status.fault.expect(false)
        } else {
          dut.io.status.fault.expect(true); dut.io.status.done.expect(false)
          dut.io.status.faultCause.expect(expectedFault); dut.io.status.faultPc.expect(faultPc)
          assert(readCsr(0x10) == expectedFault)
          faultAddr.foreach { addr =>
            dut.io.status.faultAddr.expect(addr)
            dut.io.status.faultInst.expect(words(faultPc / 4))
            assert(readCsr(0x08) == faultPc)
            assert(readCsr(0x0c) == words(faultPc / 4))
            assert(readCsr(0x14) == addr)
          }
          assert(!retired.exists(_._1 >= faultPc), s"younger commit after fault: $retired")
        }
        dut.io.debugRegs(0).expect(0)
      }

      assert(readCsr(0x18) == 256 * 1024)
      assert(readCsr(0x1c) == p.dtcmBytes)
      writeMemory(0x100000L, Seq(7, -3, 9, 11).map(x => BigInt(x) & BigInt("ffffffff", 16)))
      writeMemory(0, Seq.fill(8)(addi(1, 0, 0)) :+ halt)
      control(1, probePendingStart = true)
      until(dut.io.status.done.peek().litToBoolean)
      control(2)
      // Sum four words, exercising negative branch displacement, loads and captured store data.
      val sum = Seq(addi(1, 0, 0), addi(2, 0, 4), addi(3, 0, 0),
        lw(4, 1, 0), r(3, 0, 3, 4), addi(1, 1, 4), addi(2, 2, -1),
        b(1, 2, 0, -16), sw(3, 0, 64), fence, lw(5, 0, 64), halt)
      run(sum)
      dut.io.debugRegs(3).expect(24); dut.io.debugRegs(5).expect(24)
      assert(readMemory(0x100040L) == 24)
      assert(retired.map(_._1).toSeq == Seq(0L, 4L, 8L) ++
        Seq.fill(4)(Seq(12L, 16L, 20L, 24L, 28L)).flatten ++ Seq(32L, 36L, 40L, 44L))
      assert(readCsr(0x114) == 24)

      // Many dependent writes force sequence and per-slot generation drains repeatedly.
      run(Seq(addi(10, 0, 0)) ++ Seq.fill(80)(addi(10, 10, 1)) ++ Seq(halt))
      dut.io.debugRegs(10).expect(80)
      assert(retired.map(_._1).toSeq == (0 to 81).map(_ * 4L))

      // JALR consumes the old x6 before replacing x6; wrong-path zero must not fault.
      run(Seq(addi(6, 0, 17), i(0x67, 6, 0, 6, 0), BigInt(0), addi(7, 0, 99),
        addi(7, 6, 1), halt))
      dut.io.debugRegs(6).expect(8); dut.io.debugRegs(7).expect(9)
      assert(retired.map(_._1).toSeq == Seq(0L, 4L, 16L, 20L))
      run(Seq(b(1, 0, 0, 2), addi(8, 0, 13), halt))
      dut.io.debugRegs(8).expect(13) // not-taken encoded misaligned target is ignored

      // Faulting instructions and younger stores must not change committed state/memory.
      run(Seq(addi(9, 0, 27), BigInt("08000033", 16), sw(9, 0, 64), halt), 1, 4)
      dut.io.debugRegs(9).expect(27)
      assert(readMemory(0x100040L) == 24)
      run(Seq(lw(0, 0, 2), sw(9, 0, 64), halt), 4, 0)
      assert(readMemory(0x100040L) == 24)
      run(Seq(sw(9, 0, 2), halt), 6, 0)
      assert(readMemory(0x100000L) == 7)
      run(Seq(lw(0, 0, p.dtcmBytes), halt), 5, 0)
      run(Seq(addi(11, 0, 5), jal(11, 2), halt), 2, 4)
      dut.io.debugRegs(11).expect(5) // no link write on target fault
      run(Seq(addi(12, 0, 6), i(0x67, 12, 0, 12, 0), halt), 2, 4)
      dut.io.debugRegs(12).expect(6)
      run(Seq(BigInt("000800b7", 16), i(0x67, 0, 0, 1, 0), halt), 3, 4)
      run(Seq(addi(13, 0, 31), fence, halt))
      dut.io.debugRegs(13).expect(31)

      // Byte/halfword accesses, privileged/system encodings and non-HALT custom-0
      // words are illegal and must not create younger writes.
      val illegal = Seq(
        i(3, 20, 0, 1, 0), i(3, 20, 1, 1, 0), i(3, 20, 4, 1, 0), i(3, 20, 5, 1, 0),
        sb(2, 1, 0), sh(2, 1, 0), BigInt(0),
        halt | 0x80, halt | (BigInt(1) << 12), BigInt("08000033", 16),
        BigInt("00004073", 16), BigInt("001040f3", 16), BigInt("00204073", 16))
      for (trap <- illegal) {
        run(Seq(addi(24, 0, 37), addi(25, 0, 11), sw(24, 0, 160), trap,
          addi(25, 0, 99), sb(25, 0, 160), halt), 1, 12, Some(trap))
        dut.io.debugRegs(24).expect(37); dut.io.debugRegs(25).expect(11)
        assert(readMemory(0x1000a0L) == 37)
        assert(retired.map(_._1).toSeq == Seq(0L, 4L, 8L))
        // Every fault is acknowledged and followed by a successful draining HALT.
        run(Seq(addi(25, 24, 1), sw(25, 0, 160), halt, sw(24, 0, 160)))
        dut.io.debugRegs(25).expect(38)
        assert(readMemory(0x1000a0L) == 38)
        assert(retired.map(_._1).toSeq == Seq(0L, 4L, 8L))
      }

      // ECALL/EBREAK are precise host events delivered as distinct fault causes.
      run(Seq(ebreak, halt), 16, 0, Some(ebreak))
      run(Seq(ecall, halt), 17, 0, Some(ecall))
      // Illegal CSR access: unknown addresses and writes to read-only CSRs.
      run(Seq(addi(24, 0, 1), csrrw(20, 0x000, 24), halt), 18, 4)
      run(Seq(addi(24, 0, 1), csrrw(20, 0xc20, 24), halt), 18, 4)

      // A host-loaded vector program: load four words, add a broadcast scalar,
      // then store the result to a disjoint DTCM region.
      import VectorEncoding._
      writeMemory(0x100000L, (0 until 4).map(i => BigInt(i + 1)))
      run(Seq(vsetivli(0, 4, vtype(sew = 2, lmul = 0)), addi(1, 0, 0),
        instruction(VecOp.vle32, 0, 1, 0),
        addi(2, 0, 5), instruction(VecOp.vadd + 1, 1, 2, 0),
        addi(3, 0, 64), instruction(VecOp.vse32, 1, 3, 0), fence, halt))
      for (i <- 0 until 4)
        assert(readMemory(0x100040L + 4L * i) == BigInt(i + 6))
      dut.io.debugVRegs(0).expect(packed32((0 until 4).map(i => BigInt(i + 1))))
      dut.io.debugVRegs(1).expect(packed32((0 until 4).map(i => BigInt(i + 6))))
      assert(retired.map(_._1).toSeq == Seq(0L, 4L, 8L, 12L, 16L, 20L, 24L, 28L, 32L))
    }
  }
}
