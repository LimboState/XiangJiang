package xiangjiang

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import device.Axi4Slave
import xiangjiang.mem.DmaEngine

class DmaHarness(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val op = Input(UOpCode()); val a = Input(UInt(32.W)); val b = Input(UInt(32.W))
    val valid = Input(Bool()); val ready = Output(Bool())
    val commit = Input(Bool()); val ack = Input(Bool())
    val result = Decoupled(new ExeResult(p)); val status = Output(new DmaStatus)
    val axi = Flipped(new Axi4Slave(p.axiIdBits))
    val req = Decoupled(new WordReq); val resp = Flipped(Decoupled(new WordResp))
  })
  val d = Module(new DmaEngine(p))
  d.io.command.bits := 0.U.asTypeOf(new IssueEntry(p))
  d.io.command.bits.uop.op := io.op
  d.io.command.bits.uop.tag.slot := 1.U; d.io.command.bits.uop.tag.generation := 2.U
  d.io.command.bits.uop.pc := 16.U; d.io.command.bits.uop.seq := 3.U
  d.io.command.bits.src1.value := io.a; d.io.command.bits.src2.value := io.b
  d.io.command.valid := io.valid; io.ready := d.io.command.ready
  d.io.commit.valid := io.commit
  d.io.commit.bits.slot := 1.U; d.io.commit.bits.generation := 2.U
  d.io.ack := io.ack; io.result <> d.io.result; io.status := d.io.status
  io.axi <> d.io.axi; io.req <> d.io.localReq; d.io.localResp <> io.resp
}

class DmaSpec extends AnyFunSuite with ChiselSim {
  test("two-dimensional DMA splits pages, buffers bursts, commits config, drains errors and retains progress") {
    simulate(new DmaHarness(XJParams(bankBytes = 1024))) { c =>
      val ext = scala.collection.mutable.Map.empty[Long, BigInt]
      val local = scala.collection.mutable.Map.empty[Long, BigInt]
      val bursts = scala.collection.mutable.ArrayBuffer.empty[(Long, Int, Boolean)]
      var read: Option[(Long, Int, Int)] = None
      var write: Option[(Long, Int, Int)] = None
      var bPending = false
      var localPending: Option[BigInt] = None
      var cycles = 0
      var failRead: Option[Long] = None
      var failWrite = false
      c.io.valid.poke(false); c.io.op.poke(UOpCode.DSETSIZE); c.io.a.poke(0); c.io.b.poke(0)
      c.io.commit.poke(false); c.io.ack.poke(false); c.io.result.ready.poke(false)
      c.io.axi.ar.ready.poke(false); c.io.axi.aw.ready.poke(false); c.io.axi.w.ready.poke(false)
      c.io.axi.r.valid.poke(false); c.io.axi.r.bits.data.poke(0); c.io.axi.r.bits.id.poke(0)
      c.io.axi.r.bits.resp.poke(0); c.io.axi.r.bits.last.poke(false)
      c.io.axi.b.valid.poke(false); c.io.axi.b.bits.id.poke(0); c.io.axi.b.bits.resp.poke(0)
      c.io.req.ready.poke(false); c.io.resp.valid.poke(false); c.io.resp.bits.data.poke(0); c.io.resp.bits.error.poke(false)
      c.clock.step()
      def tick(): Unit = {
        c.io.axi.ar.ready.poke(read.isEmpty && cycles % 3 != 0)
        c.io.axi.aw.ready.poke(write.isEmpty && !bPending && cycles % 3 != 0)
        c.io.axi.w.ready.poke(write.nonEmpty && cycles % 4 != 0)
        c.io.axi.r.valid.poke(read.nonEmpty && cycles % 3 != 1)
        read.foreach { case (addr, n, i) =>
          c.io.axi.r.bits.data.poke(ext.getOrElse(addr + i * 4, BigInt(0)))
          c.io.axi.r.bits.resp.poke(if (failRead.contains(addr + i * 4)) 2 else 0)
          c.io.axi.r.bits.last.poke(i == n - 1)
        }
        c.io.axi.b.valid.poke(bPending && cycles % 3 != 1)
        c.io.axi.b.bits.resp.poke(if (failWrite) 2 else 0)
        c.io.req.ready.poke(localPending.isEmpty && cycles % 4 != 0)
        c.io.resp.valid.poke(localPending.nonEmpty)
        c.io.resp.bits.data.poke(localPending.getOrElse(BigInt(0)))
        if (c.io.resp.valid.peek().litToBoolean && c.io.resp.ready.peek().litToBoolean) localPending = None
        if (c.io.req.valid.peek().litToBoolean && c.io.req.ready.peek().litToBoolean) {
          val a = c.io.req.bits.addr.peek().litValue.toLong
          assert(a >= 0 && a + 4 <= 4096 && a % 4 == 0)
          if (c.io.req.bits.write.peek().litToBoolean) local(a) = c.io.req.bits.data.peek().litValue
          localPending = Some(local.getOrElse(a, BigInt(0)))
        }
        if (c.io.axi.r.valid.peek().litToBoolean && c.io.axi.r.ready.peek().litToBoolean) {
          read = read.flatMap { case (a, n, i) => if (i == n - 1) None else Some((a, n, i + 1)) }
        }
        if (c.io.axi.b.valid.peek().litToBoolean && c.io.axi.b.ready.peek().litToBoolean) bPending = false
        if (c.io.axi.w.valid.peek().litToBoolean && c.io.axi.w.ready.peek().litToBoolean) {
          val (a, n, i) = write.get
          c.io.axi.w.bits.last.expect(i == n - 1); c.io.axi.w.bits.strb.expect(15)
          ext(a + 4 * i) = c.io.axi.w.bits.data.peek().litValue
          if (i == n - 1) { write = None; bPending = true } else write = Some((a, n, i + 1))
        }
        for ((port, outbound) <- Seq(c.io.axi.ar -> false, c.io.axi.aw -> true)) {
          if (port.valid.peek().litToBoolean && port.ready.peek().litToBoolean) {
            val a = port.bits.addr.peek().litValue.toLong; val n = port.bits.len.peek().litValue.toInt + 1
            assert(n <= 64 && a / 4096 == (a + n * 4 - 1) / 4096)
            port.bits.size.expect(2); port.bits.burst.expect(1); port.bits.id.expect(0)
            bursts += ((a, n, outbound))
            if (outbound) { assert(write.isEmpty); write = Some((a, n, 0)) }
            else { assert(read.isEmpty); read = Some((a, n, 0)) }
          }
        }
        c.clock.step(); cycles += 1
      }
      def run(op: UOpCode.Type, a: BigInt, b: BigInt, cause: Int = 0): Unit = {
        c.io.ready.expect(true); c.io.op.poke(op); c.io.a.poke(a); c.io.b.poke(b)
        c.io.valid.poke(true); tick(); c.io.valid.poke(false)
        val deadline = cycles + 15000
        while (!c.io.result.valid.peek().litToBoolean && cycles < deadline) tick()
        c.io.result.valid.expect(true); c.io.result.bits.cause.expect(cause)
        c.io.result.bits.exception.expect(cause != 0)
        c.io.result.bits.tag.slot.expect(1); c.io.result.bits.tag.generation.expect(2)
        val held = c.io.result.bits.badAddr.peek().litValue
        val detail = c.io.result.bits.detail.peek().litValue
        for (_ <- 0 until 4) {
          tick(); c.io.result.valid.expect(true); c.io.result.bits.cause.expect(cause)
          c.io.result.bits.badAddr.expect(held); c.io.result.bits.detail.expect(detail)
        }
        c.io.result.ready.poke(true); tick(); c.io.result.ready.poke(false)
        if ((op == UOpCode.DSETSIZE || op == UOpCode.DSETSTRIDE) && cause == 0) {
          c.io.ready.expect(false); c.io.commit.poke(true); tick(); c.io.commit.poke(false)
        }
        c.io.ready.expect(true)
        assert(read.isEmpty && write.isEmpty && !bPending && localPending.isEmpty)
      }
      run(UOpCode.MVIN, 0, 0, 15); assert(bursts.isEmpty)
      run(UOpCode.DSETSIZE, 300, 2)
      run(UOpCode.DSETSTRIDE, 512, 320)
      run(UOpCode.DSETSIZE, 3, 2, 15); c.io.status.rowBytes.expect(300)
      run(UOpCode.DSETSTRIDE, 1, 0, 15); c.io.status.externalStride.expect(512)
      for (r <- 0 until 2; i <- 0 until 75) ext(4092L + 512 * r + 4 * i) = BigInt(1000 * r + i)
      run(UOpCode.MVIN, 4092, 0)
      assert(bursts.take(3).toSeq == Seq((4092L, 1, false), (4096L, 64, false), (4352L, 10, false)))
      for (r <- 0 until 2; i <- 0 until 75) assert(local(320L * r + 4 * i) == BigInt(1000 * r + i))
      c.io.status.bytesDone.expect(600); c.io.status.rowsDone.expect(2)
      run(UOpCode.MVOUT, 8192, 0)
      for (r <- 0 until 2; i <- 0 until 75) assert(ext(8192L + 512 * r + 4 * i) == BigInt(1000 * r + i))
      val count = bursts.size
      run(UOpCode.MVIN, BigInt("fffffffc", 16), 0, 15)
      run(UOpCode.MVIN, 0, 4092, 15)
      run(UOpCode.MVIN, 3, 0, 15)
      assert(bursts.size == count)
      // Failed read burst is fully drained and never reaches DTCM; first burst remains.
      failRead = Some(4100)
      run(UOpCode.MVIN, 4092, 1024, 16)
      c.io.status.bytesDone.expect(4); c.io.status.rowsDone.expect(0)
      assert(local(1024) == ext(4092)); assert(!local.contains(1028))
      failRead = None; failWrite = true
      run(UOpCode.MVOUT, 12288, 0, 17)
      c.io.status.bytesDone.expect(0); c.io.status.rowsDone.expect(0)
      failWrite = false
      // Overlapping local rows execute in order; zero external stride is legal.
      run(UOpCode.DSETSIZE, 8, 3); run(UOpCode.DSETSTRIDE, 0, 4)
      ext(0) = 7; ext(4) = 9
      run(UOpCode.MVIN, 0, 2048)
      assert(Seq(2048L, 2052L, 2056L, 2060L).map(local) == Seq(7, 7, 7, 9).map(BigInt(_)))
      c.io.status.bytesDone.expect(24); c.io.status.rowsDone.expect(3)
      c.io.ack.poke(true); tick(); c.io.ack.poke(false)
      c.io.status.bytesDone.expect(0); c.io.status.rowsDone.expect(0)
      c.io.status.rowBytes.expect(8); c.io.status.rows.expect(3)
      // A huge shape/stride must not wrap the 65-bit precheck into a local alias.
      run(UOpCode.DSETSIZE, 4, BigInt("ffffffff", 16))
      run(UOpCode.DSETSTRIDE, BigInt("fffffffc", 16), BigInt("fffffffc", 16))
      val before = bursts.size; run(UOpCode.MVIN, 0, 0, 15); assert(bursts.size == before)
    }
  }

  test("wrong read ID, early LAST and missing LAST poison an owned command") {
    for (kind <- 0 until 3) simulate(new DmaHarness(XJParams(bankBytes = 1024))) { c =>
      c.io.valid.poke(false); c.io.commit.poke(false); c.io.ack.poke(false)
      c.io.op.poke(UOpCode.DSETSIZE); c.io.a.poke(8); c.io.b.poke(1); c.io.result.ready.poke(true)
      c.io.axi.ar.ready.poke(true); c.io.axi.aw.ready.poke(true); c.io.axi.w.ready.poke(true)
      c.io.axi.b.valid.poke(false); c.io.axi.b.bits.id.poke(0); c.io.axi.b.bits.resp.poke(0)
      c.io.axi.r.valid.poke(false); c.io.axi.r.bits.id.poke(0); c.io.axi.r.bits.resp.poke(0)
      c.io.axi.r.bits.data.poke(0); c.io.axi.r.bits.last.poke(false)
      c.io.req.ready.poke(true); c.io.resp.valid.poke(false); c.io.resp.bits.data.poke(0); c.io.resp.bits.error.poke(false)
      c.clock.step()
      def config(op: UOpCode.Type, a: Int, b: Int): Unit = {
        c.io.op.poke(op); c.io.a.poke(a); c.io.b.poke(b)
        c.io.valid.poke(true); c.clock.step(); c.io.valid.poke(false)
        c.clock.step(); c.io.result.valid.expect(true); c.clock.step()
        c.io.commit.poke(true); c.clock.step(); c.io.commit.poke(false); c.io.ready.expect(true)
      }
      config(UOpCode.DSETSIZE, 8, 1); config(UOpCode.DSETSTRIDE, 0, 0)
      c.io.op.poke(UOpCode.MVIN); c.io.a.poke(0); c.io.b.poke(0)
      c.io.valid.poke(true); c.clock.step(); c.io.valid.poke(false)
      c.clock.step(2); c.io.axi.ar.valid.expect(true); c.clock.step()
      c.io.axi.r.valid.poke(true)
      if (kind == 0) c.io.axi.r.bits.id.poke(1)
      if (kind == 1) c.io.axi.r.bits.last.poke(true)
      c.clock.step()
      if (kind == 2) c.clock.step() // second beat omits LAST
      c.io.axi.r.valid.poke(false)
      c.io.status.poisoned.expect(true); c.io.status.active.expect(true)
      c.io.result.valid.expect(false); c.io.req.valid.expect(false)
      c.io.ack.poke(true); c.clock.step(3); c.io.status.poisoned.expect(true)
    }
  }

  test("unowned AXI response poisons the bus until platform reset") {
    simulate(new DmaHarness(XJParams(bankBytes = 1024))) { c =>
      c.io.valid.poke(false); c.io.commit.poke(false); c.io.ack.poke(false)
      c.io.op.poke(UOpCode.MVIN); c.io.a.poke(0); c.io.b.poke(0); c.io.result.ready.poke(true)
      c.io.axi.ar.ready.poke(true); c.io.axi.aw.ready.poke(true); c.io.axi.w.ready.poke(true)
      c.io.axi.b.valid.poke(false); c.io.axi.b.bits.id.poke(0); c.io.axi.b.bits.resp.poke(0)
      c.io.axi.r.valid.poke(false); c.io.axi.r.bits.id.poke(1); c.io.axi.r.bits.resp.poke(0)
      c.io.axi.r.bits.data.poke(0); c.io.axi.r.bits.last.poke(true)
      c.io.req.ready.poke(true); c.io.resp.valid.poke(false); c.io.resp.bits.data.poke(0); c.io.resp.bits.error.poke(false)
      c.clock.step(); c.io.axi.r.valid.poke(true); c.clock.step(); c.io.axi.r.valid.poke(false)
      c.io.status.poisoned.expect(true); c.io.status.active.expect(true)
      c.io.ack.poke(true); c.io.valid.poke(true); c.clock.step(5)
      c.io.ready.expect(false); c.io.result.valid.expect(false); c.io.status.poisoned.expect(true)
      c.io.axi.ar.valid.expect(false); c.io.axi.aw.valid.expect(false); c.io.req.valid.expect(false)
    }
  }
}
