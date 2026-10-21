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
  def lw(rd: Int, rs1: Int, imm: Int): BigInt = i(3, rd, 2, rs1, imm)
  def sw(rs2: Int, rs1: Int, imm: Int): BigInt =
    (BigInt((imm >> 5) & 0x7f) << 25) | (rs2 << 20) | (rs1 << 15) | (2 << 12) | ((imm & 31) << 7) | 0x23
  def b(f3: Int, rs1: Int, rs2: Int, imm: Int): BigInt =
    (BigInt((imm >> 12) & 1) << 31) | (((imm >> 5) & 63) << 25) | (rs2 << 20) |
      (rs1 << 15) | (f3 << 12) | (((imm >> 1) & 15) << 8) | (((imm >> 11) & 1) << 7) | 0x63
  def jal(rd: Int, imm: Int): BigInt =
    (BigInt((imm >> 20) & 1) << 31) | (((imm >> 1) & 1023) << 21) |
      (((imm >> 11) & 1) << 20) | (((imm >> 12) & 255) << 12) | (rd << 7) | 0x6f
  val fence: BigInt = BigInt("0ff0000f", 16)
  val halt: BigInt = 0
}

class XiangJiangNPUSpec extends AnyFunSuite with ChiselSim {
  test("executes host-loaded programs with ordered commits, barriers, precise faults and restart") {
    val p = XJParams(nRob = 4, nIssue = 1, nXRes = 1, nMemq = 1,
      generationBits = 1, seqBits = 4, bankBytes = 128)
    simulate(new XiangJiangNPU(p)) { dut =>
      import Rv32._
      val retired = scala.collection.mutable.ArrayBuffer.empty[(Long, Int, BigInt)]
      def tick(): Unit = {
        if (dut.io.retire.valid.peek().litToBoolean) {
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
      def control(value: Int): Unit = {
        // Exercise W-before-AW acceptance on every command.
        c.w.bits.data.poke(value); c.w.valid.poke(true)
        until(c.w.ready.peek().litToBoolean); tick(); c.w.valid.poke(false)
        c.aw.valid.poke(true)
        until(c.aw.ready.peek().litToBoolean); tick(); c.aw.valid.poke(false)
        until(c.b.valid.peek().litToBoolean); c.b.bits.expect(0)
        c.b.ready.poke(true); tick(); c.b.ready.poke(false)
      }
      def readCsr(addr: Int): BigInt = {
        c.ar.bits.addr.poke(addr); c.ar.valid.poke(true)
        until(c.ar.ready.peek().litToBoolean); tick(); c.ar.valid.poke(false)
        until(c.r.valid.peek().litToBoolean); c.r.bits.resp.expect(0)
        val value = c.r.bits.data.peek().litValue
        c.r.ready.poke(true); tick(); c.r.ready.poke(false)
        value
      }
      def run(words: Seq[BigInt], expectedFault: Int = 0, faultPc: Int = 0): Unit = {
        if (dut.io.status.done.peek().litToBoolean || dut.io.status.fault.peek().litToBoolean) control(2)
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
          assert(!retired.exists(_._1 >= faultPc), s"younger commit after fault: $retired")
        }
        dut.io.debugRegs(0).expect(0)
      }

      assert(readCsr(0x18) == 512 * 1024)
      assert(readCsr(0x1c) == p.dtcmBytes)
      writeMemory(0x10000000L, Seq(7, -3, 9, 11).map(x => BigInt(x) & BigInt("ffffffff", 16)))
      // Sum four words, exercising negative branch displacement, loads and captured store data.
      val sum = Seq(addi(1, 0, 0), addi(2, 0, 4), addi(3, 0, 0),
        lw(4, 1, 0), r(3, 0, 3, 4), addi(1, 1, 4), addi(2, 2, -1),
        b(1, 2, 0, -16), sw(3, 0, 64), fence, lw(5, 0, 64), halt)
      run(sum)
      dut.io.debugRegs(3).expect(24); dut.io.debugRegs(5).expect(24)
      assert(readMemory(0x10000040L) == 24)
      assert(retired.map(_._1).toSeq == Seq(0L, 4L, 8L) ++
        Seq.fill(4)(Seq(12L, 16L, 20L, 24L, 28L)).flatten ++ Seq(32L, 36L, 40L, 44L))
      assert(readCsr(0x114) == 24)

      // Many dependent writes force sequence and per-slot generation drains repeatedly.
      run(Seq(addi(10, 0, 0)) ++ Seq.fill(80)(addi(10, 10, 1)) ++ Seq(halt))
      dut.io.debugRegs(10).expect(80)
      assert(retired.map(_._1).toSeq == (0 to 81).map(_ * 4L))

      // JALR consumes the old x6 before replacing x6 with the link; wrong-path zero is not HALT.
      run(Seq(addi(6, 0, 17), i(0x67, 6, 0, 6, 0), halt, addi(7, 0, 99),
        addi(7, 6, 1), halt))
      dut.io.debugRegs(6).expect(8); dut.io.debugRegs(7).expect(9)
      assert(retired.map(_._1).toSeq == Seq(0L, 4L, 16L, 20L))
      run(Seq(b(1, 0, 0, 2), addi(8, 0, 13), halt))
      dut.io.debugRegs(8).expect(13) // not-taken encoded misaligned target is ignored

      // Faulting instructions and younger stores must not change committed state/memory.
      run(Seq(addi(9, 0, 27), BigInt("02000033", 16), sw(9, 0, 64), halt), 1, 4)
      dut.io.debugRegs(9).expect(27)
      assert(readMemory(0x10000040L) == 24)
      run(Seq(lw(0, 0, 2), sw(9, 0, 64), halt), 4, 0)
      assert(readMemory(0x10000040L) == 24)
      run(Seq(sw(9, 0, 2), halt), 6, 0)
      assert(readMemory(0x10000000L) == 7)
      run(Seq(lw(0, 0, p.dtcmBytes), halt), 5, 0)
      run(Seq(addi(11, 0, 5), jal(11, 2), halt), 2, 4)
      dut.io.debugRegs(11).expect(5) // no link write on target fault
      run(Seq(addi(12, 0, 6), i(0x67, 12, 0, 12, 0), halt), 2, 4)
      dut.io.debugRegs(12).expect(6)
      run(Seq(BigInt("000800b7", 16), i(0x67, 0, 0, 1, 0), halt), 3, 4)
      run(Seq(addi(13, 0, 31), fence, halt))
      dut.io.debugRegs(13).expect(31)
    }
  }
}
