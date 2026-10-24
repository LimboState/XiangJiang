package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import device.{AxiAddress, ControlRegisters, MemorySubsystem}
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.mem.DataTcm

class MemorySpec extends AnyFunSuite with ChiselSim {
  private val p = XJParams(bankBytes = 256)
  private val base = BigInt("10000000", 16)

  private def until(dut: Module, condition: => Boolean): Unit = {
    var cycles = 0
    while (!condition && cycles < 3000) { dut.clock.step(); cycles += 1 }
    assert(condition, "bounded hardware transaction timed out")
  }
  private def address(a: AxiAddress, addr: BigInt, len: Int, size: Int, burst: Int, id: Int): Unit = {
    a.addr.poke(addr.U); a.len.poke(len.U); a.size.poke(size.U)
    a.burst.poke(burst.U); a.id.poke(id.U); a.lock.poke(0.U)
    a.cache.poke(0.U); a.prot.poke(0.U); a.qos.poke(0.U)
  }
  private class MemoryDriver(val dut: MemorySubsystem) {
    dut.io.coreBusy.poke(false.B)
    dut.io.axi.aw.valid.poke(false.B); dut.io.axi.w.valid.poke(false.B)
    dut.io.axi.ar.valid.poke(false.B); dut.io.axi.b.ready.poke(false.B)
    dut.io.axi.r.ready.poke(false.B)
    address(dut.io.axi.aw.bits, 0, 0, 2, 1, 0)
    address(dut.io.axi.ar.bits, 0, 0, 2, 1, 0)
    dut.io.axi.w.bits.data.poke(0.U); dut.io.axi.w.bits.strb.poke(0.U)
    dut.io.axi.w.bits.last.poke(false.B)
    dut.io.fetchReq.valid.poke(false.B); dut.io.fetchReq.bits.poke(0.U)
    dut.io.dataReq.valid.poke(false.B); dut.io.dataReq.bits.addr.poke(0.U)
    dut.io.dataReq.bits.data.poke(0.U); dut.io.dataReq.bits.mask.poke(15.U)
    dut.io.dataReq.bits.write.poke(false.B); dut.io.dataResp.ready.poke(false.B)
    dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)

    def aw(addr: BigInt, len: Int = 0, size: Int = 2, burst: Int = 1, id: Int = 9): Unit = {
      address(dut.io.axi.aw.bits, addr, len, size, burst, id)
      dut.io.axi.aw.valid.poke(true.B)
      until(dut, dut.io.axi.aw.ready.peek().litToBoolean)
      dut.clock.step(); dut.io.axi.aw.valid.poke(false.B)
    }
    def w(data: BigInt, mask: Int = 15, last: Boolean = true): Unit = {
      dut.io.axi.w.bits.data.poke(data.U); dut.io.axi.w.bits.strb.poke(mask.U)
      dut.io.axi.w.bits.last.poke(last.B); dut.io.axi.w.valid.poke(true.B)
      until(dut, dut.io.axi.w.ready.peek().litToBoolean)
      dut.clock.step(); dut.io.axi.w.valid.poke(false.B)
    }
    def b(error: Boolean = false, id: Int = 9, stall: Int = 2): Unit = {
      until(dut, dut.io.axi.b.valid.peek().litToBoolean)
      for (_ <- 0 to stall) {
        dut.io.axi.b.valid.expect(true.B); dut.io.axi.b.bits.id.expect(id.U)
        dut.io.axi.b.bits.resp.expect((if (error) 2 else 0).U)
        dut.io.hostIdle.expect(false.B)
        if (stall > 0) dut.clock.step()
      }
      dut.io.axi.b.ready.poke(true.B); dut.clock.step(); dut.io.axi.b.ready.poke(false.B)
    }
    def write(addr: BigInt, value: BigInt, mask: Int = 15, size: Int = 2): Unit = {
      aw(addr, size = size); w(value, mask); b()
    }
    def ar(addr: BigInt, len: Int = 0, size: Int = 2, burst: Int = 1, id: Int = 6): Unit = {
      address(dut.io.axi.ar.bits, addr, len, size, burst, id)
      dut.io.axi.ar.valid.poke(true.B)
      until(dut, dut.io.axi.ar.ready.peek().litToBoolean)
      dut.clock.step(); dut.io.axi.ar.valid.poke(false.B)
    }
    def r(expected: BigInt, last: Boolean = true, error: Boolean = false,
          id: Int = 6, stall: Int = 2): Unit = {
      until(dut, dut.io.axi.r.valid.peek().litToBoolean)
      for (_ <- 0 to stall) {
        dut.io.axi.r.valid.expect(true.B); dut.io.axi.r.bits.id.expect(id.U)
        dut.io.axi.r.bits.last.expect(last.B); dut.io.axi.r.bits.data.expect(expected.U)
        dut.io.axi.r.bits.resp.expect((if (error) 2 else 0).U)
        if (stall > 0) dut.clock.step()
      }
      dut.io.axi.r.ready.poke(true.B); dut.clock.step(); dut.io.axi.r.ready.poke(false.B)
    }
    def read(addr: BigInt, value: BigInt, size: Int = 2): Unit = { ar(addr, size = size); r(value) }
    def scalar(addr: BigInt, write: Boolean, value: BigInt = 0, error: Boolean = false): Unit = {
      dut.io.dataReq.bits.addr.poke(addr.U); dut.io.dataReq.bits.write.poke(write.B)
      dut.io.dataReq.bits.data.poke(value.U); dut.io.dataReq.bits.mask.poke(15.U)
      dut.io.dataReq.valid.poke(true.B)
      until(dut, dut.io.dataReq.ready.peek().litToBoolean)
      dut.clock.step(); dut.io.dataReq.valid.poke(false.B)
      until(dut, dut.io.dataResp.valid.peek().litToBoolean)
      for (_ <- 0 until 3) {
        dut.io.dataResp.valid.expect(true.B); dut.io.dataResp.bits.error.expect(error.B)
        dut.io.dataResp.bits.data.expect((if (write || error) BigInt(0) else value).U)
        dut.clock.step()
      }
      dut.io.dataResp.ready.poke(true.B); dut.clock.step(); dut.io.dataResp.ready.poke(false.B)
    }
  }

  test("AXI channels accept W before AW, preserve IDs, and retain independent responses") {
    simulate(new MemorySubsystem(p)) { dut =>
      val m = new MemoryDriver(dut)
      m.w(BigInt("89abcdef", 16))
      dut.io.hostIdle.expect(false.B); dut.io.axi.b.valid.expect(false.B)
      dut.clock.step(3); dut.io.axi.b.valid.expect(false.B)
      m.aw(0)
      until(dut, dut.io.axi.b.valid.peek().litToBoolean)
      // A held B does not obstruct a read, even of the just-written location.
      m.ar(0, id = 3); m.r(BigInt("89abcdef", 16), id = 3, stall = 5)
      m.b(stall = 5)
      m.write(base, 123)
      // A held R likewise does not obstruct a write on a different bank.
      m.ar(base)
      until(dut, dut.io.axi.r.valid.peek().litToBoolean)
      m.write(base + 64, 456)
      m.r(123); m.read(base + 64, 456)
      dut.io.hostIdle.expect(true.B)
      dut.io.axi.aw.valid.poke(true.B); dut.io.hostIdle.expect(false.B)
      dut.io.axi.aw.valid.poke(false.B)
      dut.io.axi.w.valid.poke(true.B); dut.io.hostIdle.expect(false.B)
      dut.io.axi.w.valid.poke(false.B)
      dut.io.axi.ar.valid.poke(true.B); dut.io.hostIdle.expect(false.B)
    }
  }

  test("simultaneous read and write channels arbitrate single-port ITCM and same-row DTCM") {
    simulate(new MemorySubsystem(p)) { dut =>
      val m = new MemoryDriver(dut)
      for (a <- Seq(BigInt(0), base, base + 64)) {
        m.write(a, 123)
        address(dut.io.axi.aw.bits, a, 0, 2, 1, 9)
        address(dut.io.axi.ar.bits, a, 0, 2, 1, 6)
        dut.io.axi.w.bits.data.poke(456.U); dut.io.axi.w.bits.strb.poke(15.U)
        dut.io.axi.w.bits.last.poke(true.B)
        dut.io.axi.aw.valid.poke(true.B); dut.io.axi.w.valid.poke(true.B)
        dut.io.axi.ar.valid.poke(true.B)
        dut.io.axi.aw.ready.expect(true.B); dut.io.axi.w.ready.expect(true.B)
        dut.io.axi.ar.ready.expect(true.B)
        dut.clock.step()
        dut.io.axi.aw.valid.poke(false.B); dut.io.axi.w.valid.poke(false.B)
        dut.io.axi.ar.valid.poke(false.B)
        m.r(456); m.b()
      }
      // Different DTCM banks retain independent read/write service.
      m.write(base, 789)
      m.aw(base + 128)
      m.ar(base)
      m.w(987)
      m.r(789); m.b(); m.read(base + 128, 987)
    }
  }

  test("FIXED, INCR and every legal WRAP length execute exact beat address sequences") {
    simulate(new MemorySubsystem(p)) { dut =>
      val m = new MemoryDriver(dut)
      m.aw(0x1000, len = 255)
      for (i <- 0 until 256) m.w(0x10000 + i, last = i == 255)
      m.b()
      m.ar(0x1000, len = 255)
      for (i <- 0 until 256) m.r(0x10000 + i, last = i == 255, stall = 0)
      m.aw(base, len = 15, burst = 0)
      for (i <- 0 until 16) m.w(i + 1, last = i == 15)
      m.b(); m.ar(base, len = 15, burst = 0)
      for (i <- 0 until 16) m.r(16, last = i == 15, stall = i % 2)
      for (beats <- Seq(2, 4, 8, 16)) {
        val boundary = base + 256
        val start = boundary + (beats - 1) * 4
        m.aw(start, len = beats - 1, burst = 2)
        for (i <- 0 until beats) m.w(beats * 100 + i, last = i == beats - 1)
        m.b()
        m.ar(start, len = beats - 1, burst = 2)
        for (i <- 0 until beats) m.r(beats * 100 + i, last = i == beats - 1, stall = 1)
        for (i <- 0 until beats) {
          val a = boundary + ((beats - 1 + i) % beats) * 4
          m.read(a, beats * 100 + i)
        }
      }
    }
  }

  test("narrow bursts select byte lanes and strobes preserve unselected SRAM bytes") {
    simulate(new MemorySubsystem(p)) { dut =>
      val m = new MemoryDriver(dut)
      for (a <- Seq(BigInt(0), base)) {
        m.write(a, BigInt("11223344", 16))
        m.write(a + 1, BigInt("0000aa00", 16), mask = 2, size = 0)
        m.write(a + 2, BigInt("bbcc0000", 16), mask = 12, size = 1)
        m.read(a, BigInt("bbccaa44", 16))
        m.read(a + 1, BigInt("aa00", 16), size = 0)
        m.read(a + 2, BigInt("bbcc0000", 16), size = 1)
        m.write(a, 0, mask = 0)
        m.read(a, BigInt("bbccaa44", 16))
        m.aw(a, len = 3, size = 0)
        for (i <- 0 until 4) m.w(BigInt(0x50 + i) << (8 * i), 1 << i, last = i == 3)
        m.b(); m.read(a, BigInt("53525150", 16))
        m.ar(a, len = 3, size = 0)
        for (i <- 0 until 4) m.r(BigInt(0x50 + i) << (8 * i), last = i == 3)
        // An out-of-lane strobe is an error and cannot corrupt neighboring bytes.
        m.aw(a + 1, size = 0); m.w(0, mask = 1); m.b(error = true)
        m.read(a, BigInt("53525150", 16))
      }
    }
  }

  test("burst prechecks reject 4KiB crossing, bounds, alignment and malformed attributes atomically") {
    simulate(new MemorySubsystem(p)) { dut =>
      val m = new MemoryDriver(dut)
      val cases = Seq(
        (BigInt(0xffc), 1, 2, 1),
        (BigInt(p.itcmBytes - 4), 1, 2, 1),
        (base + p.dtcmBytes - 4, 1, 2, 1),
        (BigInt(2), 0, 2, 1),
        (BigInt(0), 0, 3, 1),
        (BigInt(0), 0, 2, 3),
        (BigInt(0), 16, 2, 0),
        (BigInt(0), 2, 2, 2),
        (BigInt("fffffffc", 16), 1, 2, 1),
        (BigInt(p.itcmBytes), 0, 2, 1),
        (base + p.dtcmBytes, 0, 2, 1))
      for ((a, len, size, burst) <- cases) {
        val sentinel = if (a == 0xffc || a == p.itcmBytes - 4 || a == base + p.dtcmBytes - 4) a else BigInt(0)
        m.write(sentinel, BigInt("a5a55a5a", 16))
        m.aw(a, len, size, burst)
        for (i <- 0 to len) m.w(0, last = i == len)
        m.b(error = true)
        m.read(sentinel, BigInt("a5a55a5a", 16))
        m.ar(a, len, size, burst)
        for (i <- 0 to len) m.r(0, last = i == len, error = true, stall = 0)
      }
      address(dut.io.axi.aw.bits, 0, 0, 2, 1, 9)
      dut.io.axi.aw.bits.lock.poke(1.U); dut.io.axi.aw.valid.poke(true.B)
      until(dut, dut.io.axi.aw.ready.peek().litToBoolean)
      dut.clock.step(); dut.io.axi.aw.valid.poke(false.B)
      m.w(0); m.b(error = true); m.read(0, BigInt("a5a55a5a", 16))
    }
  }

  test("WLAST errors drain AWLEN beats without premature response or silent success") {
    simulate(new MemorySubsystem(p)) { dut =>
      val m = new MemoryDriver(dut)
      m.write(0, 77); m.write(4, 88)
      m.aw(0, len = 1)
      m.w(11, last = true)
      dut.clock.step(4); dut.io.axi.b.valid.expect(false.B)
      m.w(22, last = true); m.b(error = true)
      m.read(0, 77); m.read(4, 88)
      m.aw(0); m.w(33, last = false); m.b(error = true)
      m.read(0, 77)
      m.aw(0, len = 1); m.w(44, last = false); m.w(55, last = false)
      m.b(error = true); m.read(0, 44); m.read(4, 88)
    }
  }

  test("core fetch is next-cycle and scalar accesses use all banks without reset-clearing SRAM") {
    simulate(new MemorySubsystem(p)) { dut =>
      val m = new MemoryDriver(dut)
      m.write(0, BigInt("12345678", 16)); m.write(p.itcmBytes - 4, BigInt("87654321", 16))
      for (a <- Seq(0, 60, 64, 128, 192, 256, p.dtcmBytes - 4)) m.write(base + a, 1000 + a)
      dut.io.coreBusy.poke(true.B)
      for ((pc, value, cause) <- Seq(
        (0, BigInt("12345678", 16), 0), (p.itcmBytes - 4, BigInt("87654321", 16), 0),
        (2, BigInt(0), 2), (p.itcmBytes, BigInt(0), 3))) {
        dut.io.fetchReq.bits.poke(pc.U); dut.io.fetchReq.valid.poke(true.B)
        dut.io.fetchReq.ready.expect(true.B); dut.clock.step()
        dut.io.fetchReq.valid.poke(false.B)
        dut.io.fetchResp.valid.expect(true.B); dut.io.fetchResp.bits.pc.expect(pc.U)
        dut.io.fetchResp.bits.inst.expect(value.U)
        dut.io.fetchResp.bits.exception.expect((cause != 0).B); dut.io.fetchResp.bits.cause.expect(cause.U)
        dut.clock.step(); dut.io.fetchResp.valid.expect(false.B)
      }
      for (a <- Seq(0, 60, 64, 128, 192, 256, p.dtcmBytes - 4)) {
        m.scalar(a, write = false, value = 1000 + a)
        m.scalar(a, write = true, value = 2000 + a)
        m.scalar(a, write = false, value = 2000 + a)
      }
      for (a <- Seq(BigInt(2), BigInt(p.dtcmBytes), base)) {
        m.scalar(a, write = true, value = 999, error = true)
        m.scalar(a, write = false, error = true)
      }
      // Busy is sampled at AW/AR acceptance, not postponed until the core stops.
      m.aw(0); m.w(0); m.b(error = true)
      m.ar(0); m.r(0, error = true)
      m.aw(base); m.w(0); m.b(error = true)
      m.ar(base); m.r(0, error = true)
      m.w(0)
      dut.io.coreBusy.poke(false.B)
      m.aw(0); m.b(error = true)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      m.read(0, BigInt("12345678", 16))
      for (a <- Seq(0, 60, 64, 128, 192, 256, p.dtcmBytes - 4)) m.read(base + a, 2000 + a)
    }
  }

  test("physical DTCM exposes four simultaneous 512-bit read and independent write ports") {
    simulate(new DataTcm(p)) { dut =>
      val old = (0 until 4).map(b => (0 until 64).foldLeft(BigInt(0))((v, i) => v | (BigInt((b * 64 + i) & 255) << (8 * i))))
      val next = old.map(_ ^ ((BigInt(1) << 512) - 1))
      for (b <- 0 until 4) {
        dut.io.read(b).valid.poke(false.B); dut.io.read(b).bits.row.poke(0.U)
        dut.io.write(b).valid.poke(true.B); dut.io.write(b).bits.row.poke(0.U)
        dut.io.write(b).bits.data.poke(old(b).U)
        dut.io.write(b).bits.mask.poke(((BigInt(1) << 64) - 1).U)
      }
      dut.clock.step()
      for (b <- 0 until 4) {
        dut.io.read(b).valid.poke(true.B)
        dut.io.write(b).bits.row.poke(1.U); dut.io.write(b).bits.data.poke(next(b).U)
      }
      dut.clock.step()
      for (b <- 0 until 4) {
        dut.io.readData(b).expect(old(b).U)
        dut.io.read(b).bits.row.poke(1.U); dut.io.write(b).valid.poke(false.B)
      }
      dut.clock.step()
      for (b <- 0 until 4) dut.io.readData(b).expect(next(b).U)
    }
  }

  test("AXI-Lite start and acknowledgement require complete permitted writes and retain responses") {
    simulate(new ControlRegisters(p)) { dut =>
      dut.io.axi.aw.valid.poke(false.B); dut.io.axi.aw.bits.addr.poke(0.U); dut.io.axi.aw.bits.prot.poke(0.U)
      dut.io.axi.w.valid.poke(false.B); dut.io.axi.w.bits.data.poke(0.U); dut.io.axi.w.bits.strb.poke(0.U)
      dut.io.axi.ar.valid.poke(false.B); dut.io.axi.ar.bits.addr.poke(0.U); dut.io.axi.ar.bits.prot.poke(0.U)
      dut.io.axi.b.ready.poke(false.B); dut.io.axi.r.ready.poke(false.B)
      dut.io.hostIdle.poke(true.B)
      dut.io.status.busy.poke(false.B); dut.io.status.done.poke(false.B); dut.io.status.fault.poke(false.B)
      dut.io.status.faultPc.poke(20.U); dut.io.status.faultInst.poke(21.U)
      dut.io.status.faultCause.poke(7.U); dut.io.status.faultAddr.poke(23.U)
      for (i <- 0 until 32) dut.io.debugRegs(i).poke((if (i == 0) 0 else 100 + i).U)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      def read(addr: Int, expected: BigInt, error: Boolean = false): Unit = {
        dut.io.axi.ar.bits.addr.poke(addr.U); dut.io.axi.ar.valid.poke(true.B)
        until(dut, dut.io.axi.ar.ready.peek().litToBoolean)
        dut.clock.step(); dut.io.axi.ar.valid.poke(false.B)
        until(dut, dut.io.axi.r.valid.peek().litToBoolean)
        for (_ <- 0 until 3) {
          dut.io.axi.r.bits.resp.expect((if (error) 2 else 0).U)
          if (!error) dut.io.axi.r.bits.data.expect(expected.U)
          dut.clock.step(); dut.io.axi.r.valid.expect(true.B)
        }
        dut.io.axi.r.ready.poke(true.B); dut.clock.step(); dut.io.axi.r.ready.poke(false.B)
      }
      def write(addr: Int, value: Long, mask: Int = 15, error: Boolean = false,
                start: Boolean = false, ack: Boolean = false, wFirst: Boolean = false): Unit = {
        dut.io.axi.aw.bits.addr.poke(addr.U)
        dut.io.axi.w.bits.data.poke(value.U); dut.io.axi.w.bits.strb.poke(mask.U)
        if (wFirst) dut.io.axi.w.valid.poke(true.B) else dut.io.axi.aw.valid.poke(true.B)
        until(dut, if (wFirst) dut.io.axi.w.ready.peek().litToBoolean else dut.io.axi.aw.ready.peek().litToBoolean)
        dut.clock.step()
        dut.io.axi.aw.valid.poke(false.B); dut.io.axi.w.valid.poke(false.B)
        dut.io.start.expect(false.B); dut.io.ack.expect(false.B); dut.clock.step(2)
        if (wFirst) dut.io.axi.aw.valid.poke(true.B) else dut.io.axi.w.valid.poke(true.B)
        until(dut, if (wFirst) dut.io.axi.aw.ready.peek().litToBoolean else dut.io.axi.w.ready.peek().litToBoolean)
        dut.clock.step()
        dut.io.axi.aw.valid.poke(false.B); dut.io.axi.w.valid.poke(false.B)
        dut.io.start.expect(start.B); dut.io.ack.expect(ack.B)
        dut.clock.step()
        dut.io.start.expect(false.B); dut.io.ack.expect(false.B)
        until(dut, dut.io.axi.b.valid.peek().litToBoolean)
        // CSR reads remain independent while the write response is held.
        read(0x30, 1)
        for (_ <- 0 until 3) {
          dut.io.axi.b.valid.expect(true.B); dut.io.axi.b.bits.expect((if (error) 2 else 0).U)
          dut.io.start.expect(false.B); dut.io.ack.expect(false.B); dut.clock.step()
        }
        dut.io.axi.b.ready.poke(true.B); dut.clock.step(); dut.io.axi.b.ready.poke(false.B)
      }
      write(0, 1, start = true, wFirst = true)
      write(0, 2, ack = true)
      write(0, 3, error = true)
      write(0, 1, mask = 14)
      dut.io.hostIdle.poke(false.B); write(0, 1, error = true)
      dut.io.hostIdle.poke(true.B); dut.io.status.done.poke(true.B)
      write(0, 1, error = true); write(0, 2, ack = true)
      dut.io.status.done.poke(false.B); dut.io.status.fault.poke(true.B)
      write(0, 1, error = true); write(0, 2, ack = true)
      dut.io.status.busy.poke(true.B)
      write(0, 1, error = true); write(0, 2, error = true)
      read(4, 5)
      dut.io.status.busy.poke(false.B); dut.io.status.fault.poke(false.B)
      for ((a, v) <- Seq(0 -> 0, 4 -> 0, 8 -> 20, 12 -> 21, 16 -> 7, 20 -> 23,
        24 -> p.itcmBytes, 28 -> p.dtcmBytes, 32 -> p.nRob, 36 -> p.nXRes,
        40 -> p.nMemq, 44 -> p.nIssue, 48 -> 1)) read(a, v)
      for (i <- 0 until 32) read(0x100 + i * 4, if (i == 0) 0 else 100 + i)
      write(0x80, 0x11223344)
      write(0x80, 0xaabbccddL, mask = 5)
      read(0x80, BigInt("11bb33dd", 16))
      write(0x84, 0x12345678, mask = 2)
      read(0x84, BigInt("00005600", 16))
      write(0x88, 0x11223344)
      write(0x88, 0xaabbccddL, mask = 10)
      read(0x88, BigInt("aa22cc44", 16))
      write(0x8c, 0x12345678)
      write(0x8c, 0xaabbccddL, mask = 3)
      read(0x8c, BigInt("1234ccdd", 16))
      for (a <- Seq(1, 0x34, 0xfc, 0x180)) read(a, 0, error = true)
      for (a <- Seq(1, 4, 0x100, 0x180)) write(a, 1, error = true)
    }
  }
}
