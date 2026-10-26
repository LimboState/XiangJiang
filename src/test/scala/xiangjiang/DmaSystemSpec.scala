package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import top.XiangJiangNPU

object DmaEncoding {
  def apply(op: Int, rs1: Int, rs2: Int): BigInt =
    (BigInt(16 + op) << 25) | (rs2 << 20) | (rs1 << 15) | (3 << 12) | 0x2b
}

class DmaSystemSpec extends AnyFunSuite with ChiselSim {
  test("host-loaded DMA programs order memory, wait for B, fault precisely and restart") {
    val p = XJParams(nRob = 4, nMemq = 2, generationBits = 1, seqBits = 5, bankBytes = 256)
    simulate(new XiangJiangNPU(p)) { d =>
      import Rv32._
      val m = d.io.memory; val c = d.io.control; val a = d.io.ddr
      val ddr = scala.collection.mutable.Map.empty[Long, BigInt]
      for (i <- 0 until 64) ddr(0x1000L + i * 4) = BigInt(100 + i)
      var read: Option[(Long, Int, Int)] = None
      var write: Option[(Long, Int, Int)] = None
      var bDelay = -1
      var injectReadError = false
      var injectWriteError = false
      var transactions = 0
      var cycles = 0
      val commits = scala.collection.mutable.ArrayBuffer.empty[Int]
      m.aw.valid.poke(false); m.w.valid.poke(false); m.ar.valid.poke(false)
      m.b.ready.poke(false); m.r.ready.poke(false)
      c.aw.valid.poke(false); c.w.valid.poke(false); c.ar.valid.poke(false)
      c.b.ready.poke(false); c.r.ready.poke(false)
      for (ad <- Seq(m.aw.bits, m.ar.bits)) {
        ad.id.poke(1); ad.len.poke(0); ad.size.poke(2); ad.burst.poke(1)
        ad.lock.poke(0); ad.cache.poke(0); ad.prot.poke(0); ad.qos.poke(0)
      }
      c.aw.bits.addr.poke(0); c.aw.bits.prot.poke(0); c.w.bits.strb.poke(15)
      c.ar.bits.prot.poke(0)
      m.w.bits.strb.poke(15); m.w.bits.last.poke(true)
      def tick(): Unit = {
        a.ar.ready.poke(cycles % 3 != 0); a.aw.ready.poke(cycles % 3 != 0)
        a.w.ready.poke(cycles % 2 == 0)
        a.r.valid.poke(read.isDefined)
        read.foreach { case (addr, n, i) =>
          a.r.bits.data.poke(ddr.getOrElse(addr + 4 * i, BigInt(0)))
          a.r.bits.last.poke(i == n - 1); a.r.bits.id.poke(0)
          a.r.bits.resp.poke(if (injectReadError) 2 else 0)
        }
        a.b.valid.poke(bDelay == 0); a.b.bits.id.poke(0)
        a.b.bits.resp.poke(if (injectWriteError) 2 else 0)
        if (bDelay > 0) { d.io.status.busy.expect(true); d.io.status.done.expect(false); bDelay -= 1 }
        if (a.r.valid.peek().litToBoolean && a.r.ready.peek().litToBoolean) {
          val (addr, n, i) = read.get
          read = if (i + 1 == n) None else Some((addr, n, i + 1))
        }
        if (a.b.valid.peek().litToBoolean && a.b.ready.peek().litToBoolean) bDelay = -1
        if (a.ar.valid.peek().litToBoolean && a.ar.ready.peek().litToBoolean) {
          assert(read.isEmpty && write.isEmpty && bDelay == -1)
          transactions += 1
          read = Some((a.ar.bits.addr.peek().litValue.toLong, a.ar.bits.len.peek().litValue.toInt + 1, 0))
        }
        if (a.aw.valid.peek().litToBoolean && a.aw.ready.peek().litToBoolean) {
          assert(read.isEmpty && write.isEmpty && bDelay == -1)
          transactions += 1
          write = Some((a.aw.bits.addr.peek().litValue.toLong, a.aw.bits.len.peek().litValue.toInt + 1, 0))
        }
        if (a.w.valid.peek().litToBoolean && a.w.ready.peek().litToBoolean) {
          val (addr, n, i) = write.get
          ddr(addr + i * 4) = a.w.bits.data.peek().litValue
          if (i + 1 == n) { write = None; bDelay = 25 }
          else write = Some((addr, n, i + 1))
        }
        if (d.io.retire.valid.peek().litToBoolean) commits += d.io.retire.bits.uop.pc.peek().litValue.toInt
        d.clock.step(); cycles += 1
      }
      def until(cond: => Boolean): Unit = {
        val end = cycles + 10000
        while (!cond && cycles < end) tick()
        assert(cond, s"timeout; commits=$commits")
      }
      def put(addr: Long, value: BigInt): Unit = {
        m.aw.bits.addr.poke(addr); m.aw.valid.poke(true)
        until(m.aw.ready.peek().litToBoolean); tick(); m.aw.valid.poke(false)
        m.w.bits.data.poke(value); m.w.valid.poke(true)
        until(m.w.ready.peek().litToBoolean); tick(); m.w.valid.poke(false)
        until(m.b.valid.peek().litToBoolean); m.b.bits.resp.expect(0)
        m.b.ready.poke(true); tick(); m.b.ready.poke(false)
      }
      def csr(addr: Int): BigInt = {
        c.ar.bits.addr.poke(addr); c.ar.valid.poke(true)
        until(c.ar.ready.peek().litToBoolean); tick(); c.ar.valid.poke(false)
        until(c.r.valid.peek().litToBoolean); c.r.bits.resp.expect(0)
        val value = c.r.bits.data.peek().litValue
        c.r.ready.poke(true); tick(); c.r.ready.poke(false); value
      }
      def control(value: Int): Unit = {
        c.aw.valid.poke(true); until(c.aw.ready.peek().litToBoolean); tick(); c.aw.valid.poke(false)
        c.w.bits.data.poke(value); c.w.valid.poke(true)
        until(c.w.ready.peek().litToBoolean); tick(); c.w.valid.poke(false)
        until(c.b.valid.peek().litToBoolean); c.b.bits.expect(0)
        c.b.ready.poke(true); tick(); c.b.ready.poke(false)
      }
      def run(words: Seq[BigInt], cause: Int = 0): Unit = {
        if (d.io.status.done.peek().litToBoolean || d.io.status.fault.peek().litToBoolean) control(2)
        for ((w, i) <- words.zipWithIndex) put(i * 4L, w)
        commits.clear(); transactions = 0
        control(1); until(!d.io.status.busy.peek().litToBoolean)
        d.io.status.fault.expect(cause != 0); d.io.status.done.expect(cause == 0)
        d.io.status.faultCause.expect(cause)
        assert(read.isEmpty && write.isEmpty && bDelay == -1)
        if (cause == 0) assert(commits.toSeq == words.indices.map(_ * 4))
      }
      val shape = Seq(addi(1, 0, 128), addi(2, 0, 1), DmaEncoding(0, 1, 2), DmaEncoding(1, 0, 0))
      val addresses = Seq(BigInt(0x00001537), addi(11, 0, 60)) // LUI x10,1
      run(shape ++ addresses ++ Seq(DmaEncoding(2, 10, 11), addi(10, 0, 0),
        lw(12, 11, 0), addi(12, 12, 1), sw(12, 11, 0),
        BigInt(0x00002537), DmaEncoding(3, 10, 11), fence, halt))
      d.io.debugRegs(12).expect(101)
      assert(ddr(0x2000) == 101)
      for (i <- 1 until 32) assert(ddr(0x2000L + i * 4) == 100 + i)
      assert(csr(0x34) == 0 && csr(0x3c) == 128 && csr(0x40) == 0)

      injectReadError = true
      run(shape ++ addresses ++ Seq(DmaEncoding(2, 10, 11), addi(20, 0, 99), sw(20, 0, 0), halt), 13)
      d.io.status.faultPc.expect(24); d.io.debugRegs(20).expect(0)
      assert(!commits.contains(24) && transactions == 1 && csr(0x34) == 13)
      injectReadError = false; injectWriteError = true
      run(shape ++ addresses ++ Seq(DmaEncoding(3, 10, 11), halt), 14)
      assert(csr(0x34) == (16 + 14) && csr(0x3c) == 0)
      injectWriteError = false
      run(shape ++ addresses ++ Seq(DmaEncoding(2, 10, 11), halt))
      run(shape ++ Seq(addi(10, 0, 2), addi(11, 0, 0), DmaEncoding(2, 10, 11), halt), 12)
      assert(transactions == 0)
      run(Seq(BigInt(0), DmaEncoding(3, 10, 11), halt), 1)
      assert(transactions == 0)
    }
  }
}
