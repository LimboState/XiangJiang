package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util._
import device._
import org.scalatest.funsuite.AnyFunSuite

class DmaHarness(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val control = new AxiLiteSlave
    val memory = new Axi4Slave(p.axiIdBits)
    val dma = new DmaAxiMaster
  })
  val control = Module(new ControlRegisters(p))
  val memory = Module(new MemorySubsystem(p))
  val dma = Module(new DmaEngine(p))
  control.io.axi <> io.control
  memory.io.axi <> io.memory
  dma.io.axi <> io.dma
  control.io.status := 0.U.asTypeOf(new CoreStatus)
  control.io.hostIdle := memory.io.hostIdle
  control.io.dmaBusy := dma.io.busy
  control.io.dmaDone := dma.io.done
  control.io.dmaError := dma.io.error
  control.io.dmaErrorCode := dma.io.errorCode
  control.io.dmaErrorAddr := dma.io.errorAddr
  control.io.debugRegs := VecInit(Seq.fill(32)(0.U(32.W)))
  dma.io.start := control.io.dmaStart
  dma.io.clear := control.io.dmaClear
  dma.io.dir := control.io.dmaDirection
  dma.io.hostAddr := control.io.dmaHostAddr
  dma.io.localAddr := control.io.dmaLocalAddr
  dma.io.length := control.io.dmaLength
  memory.io.dmaBusy := dma.io.busy
  memory.io.dmaReq <> dma.io.localReq
  dma.io.localResp <> memory.io.dmaResp
  memory.io.coreBusy := false.B
  memory.io.fetchReq.valid := false.B
  memory.io.fetchReq.bits := 0.U
  memory.io.dataReq.valid := false.B
  memory.io.dataReq.bits := 0.U.asTypeOf(new WordReq)
  memory.io.dataResp.ready := false.B
  memory.io.matrixReq.valid := false.B
  memory.io.matrixReq.bits := 0.U.asTypeOf(new MatrixBeatReq)
  memory.io.matrixResp.ready := false.B
}

/** Test the real CSR -> DMA -> DTCM path, with no mocked local memory port. */
class DmaIntegrationSpec extends AnyFunSuite with ChiselSim {
  private val p = XJParams(nRob = 4, nIssue = 1, nXRes = 1, nVRes = 1, nMemq = 1,
    generationBits = 1, seqBits = 4, bankBytes = 128)
  private val base = BigInt("10000000", 16)
  private val wordMask = (BigInt(1) << 32) - 1

  private class Driver(val dut: DmaHarness) {
    private val c = dut.io.control
    private val m = dut.io.memory
    private val a = dut.io.dma
    def waitFor(condition: => Boolean): Unit = {
      var cycles = 0
      while (!condition && cycles < 2000) { dut.clock.step(); cycles += 1 }
      assert(condition, s"DMA integration timeout after $cycles cycles")
    }
    private def address(bits: AxiAddress, addr: BigInt, len: Int): Unit = {
      bits.id.poke(3); bits.addr.poke(addr); bits.len.poke(len)
      bits.size.poke(2); bits.burst.poke(1); bits.lock.poke(0)
      bits.cache.poke(0); bits.prot.poke(0); bits.qos.poke(0)
    }
    c.aw.valid.poke(false); c.aw.bits.addr.poke(0); c.aw.bits.prot.poke(0)
    c.w.valid.poke(false); c.w.bits.data.poke(0); c.w.bits.strb.poke(15)
    c.ar.valid.poke(false); c.ar.bits.addr.poke(0); c.ar.bits.prot.poke(0)
    c.b.ready.poke(false); c.r.ready.poke(false)
    m.aw.valid.poke(false); address(m.aw.bits, 0, 0)
    m.w.valid.poke(false); m.w.bits.data.poke(0); m.w.bits.strb.poke(15); m.w.bits.last.poke(true)
    m.ar.valid.poke(false); address(m.ar.bits, 0, 0)
    m.b.ready.poke(false); m.r.ready.poke(false)
    a.ar.ready.poke(false); a.aw.ready.poke(false); a.w.ready.poke(false)
    a.r.valid.poke(false); a.r.bits.id.poke(0); a.r.bits.data.poke(0)
    a.r.bits.resp.poke(0); a.r.bits.last.poke(false)
    a.b.valid.poke(false); a.b.bits.id.poke(0); a.b.bits.resp.poke(0)
    dut.reset.poke(true); dut.clock.step(2); dut.reset.poke(false)

    def writeCsr(addr: Int, value: BigInt, error: Boolean = false,
                 mask: Int = 15, wFirst: Boolean = false): Unit = {
      c.aw.bits.addr.poke(addr); c.w.bits.data.poke(value); c.w.bits.strb.poke(mask)
      def sendAddress(): Unit = {
        c.aw.valid.poke(true); waitFor(c.aw.ready.peek().litToBoolean)
        dut.clock.step(); c.aw.valid.poke(false)
      }
      def sendData(): Unit = {
        c.w.valid.poke(true); waitFor(c.w.ready.peek().litToBoolean)
        dut.clock.step(); c.w.valid.poke(false)
      }
      if (wFirst) { sendData(); dut.clock.step(); sendAddress() }
      else { sendAddress(); dut.clock.step(); sendData() }
      waitFor(c.b.valid.peek().litToBoolean)
      for (_ <- 0 until 3) {
        c.b.valid.expect(true); c.b.bits.expect(if (error) 2 else 0); dut.clock.step()
      }
      c.b.ready.poke(true); dut.clock.step(); c.b.ready.poke(false)
    }
    def readCsr(addr: Int): BigInt = {
      c.ar.bits.addr.poke(addr); c.ar.valid.poke(true)
      waitFor(c.ar.ready.peek().litToBoolean); dut.clock.step(); c.ar.valid.poke(false)
      waitFor(c.r.valid.peek().litToBoolean); c.r.bits.resp.expect(0)
      val value = c.r.bits.data.peek().litValue
      dut.clock.step(); c.r.bits.data.expect(value)
      c.r.ready.poke(true); dut.clock.step(); c.r.ready.poke(false)
      value
    }
    def configure(host: BigInt, local: Int, bytes: Int): Unit = {
      writeCsr(0x80, host & wordMask, wFirst = true)
      writeCsr(0x84, host >> 32); writeCsr(0x88, local); writeCsr(0x8c, bytes)
    }
    def writeWords(local: Int, words: Seq[BigInt]): Unit = {
      address(m.aw.bits, base + local, words.size - 1); m.aw.valid.poke(true)
      waitFor(m.aw.ready.peek().litToBoolean); dut.clock.step(); m.aw.valid.poke(false)
      for ((word, i) <- words.zipWithIndex) {
        m.w.bits.data.poke(word); m.w.bits.last.poke(i == words.size - 1); m.w.valid.poke(true)
        waitFor(m.w.ready.peek().litToBoolean); dut.clock.step(); m.w.valid.poke(false)
      }
      waitFor(m.b.valid.peek().litToBoolean); m.b.bits.resp.expect(0)
      m.b.ready.poke(true); dut.clock.step(); m.b.ready.poke(false)
    }
    def checkWords(local: Int, words: Seq[BigInt]): Unit = {
      address(m.ar.bits, base + local, words.size - 1); m.ar.valid.poke(true)
      waitFor(m.ar.ready.peek().litToBoolean); dut.clock.step(); m.ar.valid.poke(false)
      for ((word, i) <- words.zipWithIndex) {
        waitFor(m.r.valid.peek().litToBoolean)
        m.r.bits.resp.expect(0); m.r.bits.data.expect(word); m.r.bits.last.expect(i == words.size - 1)
        m.r.ready.poke(true); dut.clock.step(); m.r.ready.poke(false)
      }
    }
    private def checkDone(): Unit = {
      assert((readCsr(0x90) & 6) == 0, "DMA must finish without busy or error")
      assert(readCsr(0xa0) == 1, "successful transfer must set done")
    }
    def receiveLoad(host: BigInt, data: Seq[BigInt]): Unit = {
      var index = 0
      while (index < data.size) {
        val addr = host + index * 16
        val beats = math.min(data.size - index, (4096 - (addr & 4095).toInt) / 16)
        a.aw.valid.expect(false); waitFor(a.ar.valid.peek().litToBoolean)
        for (_ <- 0 until 2) {
          a.ar.bits.addr.expect(addr); a.ar.bits.len.expect(beats - 1)
          a.ar.bits.size.expect(4); a.ar.bits.burst.expect(1); dut.clock.step()
        }
        a.ar.ready.poke(true); dut.clock.step(); a.ar.ready.poke(false)
        for (beat <- 0 until beats) {
          a.r.bits.data.poke(data(index + beat)); a.r.bits.last.poke(beat == beats - 1)
          a.r.valid.poke(true); waitFor(a.r.ready.peek().litToBoolean)
          dut.clock.step(); a.r.valid.poke(false)
        }
        index += beats
      }
      checkDone()
    }
    def load(host: BigInt, local: Int, data: Seq[BigInt]): Unit = {
      configure(host, local, data.size * 16); writeCsr(0x90, 1)
      assert(readCsr(0x90) == 2); assert(readCsr(0xa0) == 0)
      receiveLoad(host, data)
    }
    def store(host: BigInt, local: Int, data: Seq[BigInt]): Unit = {
      configure(host, local, data.size * 16); writeCsr(0x90, 5, wFirst = true)
      assert(readCsr(0x90) == 3); assert(readCsr(0xa0) == 0)
      var index = 0
      while (index < data.size) {
        val addr = host + index * 16
        val beats = math.min(data.size - index, (4096 - (addr & 4095).toInt) / 16)
        a.ar.valid.expect(false); waitFor(a.aw.valid.peek().litToBoolean)
        a.aw.bits.addr.expect(addr); a.aw.bits.len.expect(beats - 1); a.aw.bits.size.expect(4)
        a.aw.ready.poke(true); dut.clock.step(); a.aw.ready.poke(false)
        for (beat <- 0 until beats) {
          waitFor(a.w.valid.peek().litToBoolean)
          for (_ <- 0 until 2) {
            a.w.valid.expect(true); a.w.bits.data.expect(data(index + beat))
            a.w.bits.strb.expect(65535); a.w.bits.last.expect(beat == beats - 1); dut.clock.step()
          }
          a.w.ready.poke(true); dut.clock.step(); a.w.ready.poke(false)
        }
        a.b.valid.poke(true); waitFor(a.b.ready.peek().litToBoolean)
        dut.clock.step(); a.b.valid.poke(false); index += beats
      }
      checkDone()
    }
  }

  test("CSR-started DMA round-trips every DTCM quarter and bank while preserving neighboring bytes") {
    simulate(new DmaHarness(p)) { dut =>
      val d = new Driver(dut)
      val expected = Array.tabulate(p.dtcmBytes / 4)(i => BigInt("a5000000", 16) + i)
      d.writeWords(0, expected.toSeq)
      for (local <- 0 until p.dtcmBytes by 16) {
        val words = (0 until 4).map(i => BigInt("12340000", 16) + local * 16 + i)
        val beat = words.zipWithIndex.foldLeft(BigInt(0)) { case (value, (word, i)) => value | (word << (32 * i)) }
        val host = BigInt("1234567800001000", 16) + local
        d.load(host, local, Seq(beat))
        for (i <- 0 until 4) expected(local / 4 + i) = words(i)
        val row = local & ~63
        d.checkWords(row, expected.slice(row / 4, row / 4 + 16).toSeq)
        d.store(host + 0x10000, local, Seq(beat))
      }
      val data = (0 until 4).map(i => BigInt("fedcba98765432100123456789abc000", 16) + i)
      val host = BigInt("1234567800001ff0", 16)
      d.load(host, 0x30, data)
      for ((beat, i) <- data.zipWithIndex; word <- 0 until 4)
        expected(0x30 / 4 + i * 4 + word) = (beat >> (word * 32)) & wordMask
      d.checkWords(0, expected.toSeq)
      d.store(host + 0x10000, 0x30, data)
    }
  }

  test("DMA CSR commands reject conflicts and uncleared faults without changing direction or completion") {
    simulate(new DmaHarness(p)) { dut =>
      val d = new Driver(dut)
      d.configure(0x1000, 0, 16)
      d.writeCsr(0x90, 7, error = true); assert(d.readCsr(0x90) == 0)
      d.writeCsr(0x90, 5, mask = 14); assert(d.readCsr(0x90) == 0)
      dut.io.dma.ar.valid.expect(false); dut.io.dma.aw.valid.expect(false)
      d.writeCsr(0x8c, 0); d.writeCsr(0x90, 5)
      assert(d.readCsr(0x90) == 5); assert(d.readCsr(0x94) == 1); assert(d.readCsr(0xa0) == 0)
      d.writeCsr(0x90, 1, error = true); d.writeCsr(0x90, 3, error = true)
      assert(d.readCsr(0x90) == 5); assert(d.readCsr(0x94) == 1)
      d.writeCsr(0x90, 6); assert(d.readCsr(0x90) == 1); assert(d.readCsr(0x94) == 0)
      d.writeCsr(0x8c, 16); d.writeCsr(0x90, 1)
      assert(d.readCsr(0x90) == 2)
      d.writeCsr(0x90, 6, error = true); d.writeCsr(0x90, 5, error = true)
      d.writeCsr(0x90, 7, error = true); d.writeCsr(0x80, 0x2000, error = true)
      d.writeCsr(0, 1, error = true)
      assert(d.readCsr(0x90) == 2); assert(d.readCsr(0x80) == 0x1000)
      val data = BigInt("112233445566778899aabbccddeeff00", 16)
      d.receiveLoad(0x1000, Seq(data))
      d.writeCsr(0x90, 7, error = true)
      assert(d.readCsr(0xa0) == 1); assert(d.readCsr(0x90) == 0)
      d.writeCsr(0x90, 2); assert(d.readCsr(0xa0) == 0)
      d.store(0x2000, 0, Seq(data))
    }
  }
}
