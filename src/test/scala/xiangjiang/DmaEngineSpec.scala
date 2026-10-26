package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import device.DmaEngine

class DmaEngineSpec extends AnyFunSuite with ChiselSim {
  for (maximum <- Seq(1, 7, 256)) test(s"DMA round trips, 2D strides, boundaries, backpressure and faults (max=$maximum)") {
    val p = XJParams(bankBytes = 2048, dmaMaxBurstBeats = maximum)
    simulate(new DmaEngine(p)) { d =>
      val random = new scala.util.Random(2026)
      val local = Array.fill(p.dtcmBytes)(0xa5)
      val ddr = scala.collection.mutable.Map.empty[Long, Int]
      def ext(a: Long): Int = ddr.getOrElse(a, ((a * 37 + 11) & 255).toInt)
      def pack(xs: Seq[Int]): BigInt = xs.zipWithIndex.foldLeft(BigInt(0)) {
        case (v, (b, i)) => v | (BigInt(b) << (8 * i))
      }
      var read: Option[(Long, Int, Int)] = None
      var write: Option[(Long, Int, Int)] = None
      var response: Option[(BigInt, Boolean)] = None
      var bPending = false
      var rHeld: Option[(BigInt, Int, Int, Boolean)] = None
      var burstCount = 0
      var localCount = 0
      var faultRead = -1
      var faultWrite = -1
      var faultLocal = -1
      var wrongId = false
      var earlyLast = false
      var lateLast = false
      d.io.command.valid.poke(false); d.io.completion.ready.poke(false)
      d.io.axi.r.valid.poke(false); d.io.axi.b.valid.poke(false)
      d.io.memResp.valid.poke(false)
      var heldAr: Option[(BigInt, BigInt)] = None
      var heldAw: Option[(BigInt, BigInt)] = None
      var heldW: Option[(BigInt, Boolean)] = None
      def tick(): Unit = {
        val arReady = random.nextBoolean(); val awReady = random.nextBoolean()
        val wReady = random.nextBoolean(); val mReady = random.nextBoolean()
        d.io.axi.ar.ready.poke(arReady); d.io.axi.aw.ready.poke(awReady)
        d.io.axi.w.ready.poke(wReady); d.io.memReq.ready.poke(mReady)
        if (rHeld.isEmpty && read.isDefined && random.nextBoolean()) {
          val (a, n, i) = read.get
          val last = if (lateLast) i == n else if (earlyLast && i == 0) true else i == n - 1
          rHeld = Some((pack((0 until 4).map(j => ext(a + i * 4 + j))),
            if (burstCount == faultRead) 2 else 0, if (wrongId) 1 else 0, last))
        }
        d.io.axi.r.valid.poke(rHeld.isDefined)
        rHeld.foreach { case (data, resp, id, last) =>
          d.io.axi.r.bits.data.poke(data); d.io.axi.r.bits.resp.poke(resp)
          d.io.axi.r.bits.id.poke(id); d.io.axi.r.bits.last.poke(last)
        }
        d.io.axi.b.valid.poke(bPending)
        d.io.axi.b.bits.resp.poke(if (burstCount == faultWrite) 2 else 0)
        d.io.axi.b.bits.id.poke(if (wrongId) 1 else 0)
        d.io.memResp.valid.poke(response.isDefined)
        response.foreach { case (data, error) =>
          d.io.memResp.bits.data.poke(data); d.io.memResp.bits.error.poke(error)
        }
        def addr(a: device.AxiAddress): (BigInt, BigInt) = (a.addr.peek().litValue, a.len.peek().litValue)
        val ar = addr(d.io.axi.ar.bits); val aw = addr(d.io.axi.aw.bits)
        val w = (d.io.axi.w.bits.data.peek().litValue, d.io.axi.w.bits.last.peek().litToBoolean)
        heldAr.foreach(x => { d.io.axi.ar.valid.expect(true); assert(x == ar) })
        heldAw.foreach(x => { d.io.axi.aw.valid.expect(true); assert(x == aw) })
        heldW.foreach(x => { d.io.axi.w.valid.expect(true); assert(x == w) })
        heldAr = if (d.io.axi.ar.valid.peek().litToBoolean && !arReady) Some(ar) else None
        heldAw = if (d.io.axi.aw.valid.peek().litToBoolean && !awReady) Some(aw) else None
        heldW = if (d.io.axi.w.valid.peek().litToBoolean && !wReady) Some(w) else None
        if (rHeld.isDefined && d.io.axi.r.ready.peek().litToBoolean) {
          val (a, n, i) = read.get
          read = if (i == n - 1 + (if (lateLast) 1 else 0)) None else Some((a, n, i + 1))
          rHeld = None
        }
        if (bPending && d.io.axi.b.ready.peek().litToBoolean) bPending = false
        if (response.isDefined && d.io.memResp.ready.peek().litToBoolean) response = None
        for ((channel, ready, isWrite) <- Seq((d.io.axi.ar, arReady, false), (d.io.axi.aw, awReady, true))) {
          if (channel.valid.peek().litToBoolean && ready) {
            assert(read.isEmpty && write.isEmpty && !bPending)
            val a = channel.bits.addr.peek().litValue.toLong
            val n = channel.bits.len.peek().litValue.toInt + 1
            assert(n <= maximum && n <= 16 && (a & 4095) + n * 4 <= 4096)
            channel.bits.size.expect(2); channel.bits.burst.expect(1); channel.bits.id.expect(0)
            burstCount += 1
            if (isWrite) write = Some((a, n, 0)) else read = Some((a, n, 0))
          }
        }
        if (d.io.axi.w.valid.peek().litToBoolean && wReady) {
          val (a, n, i) = write.get
          assert(w._2 == (i == n - 1)); d.io.axi.w.bits.strb.expect(15)
          for (j <- 0 until 4) ddr(a + i * 4 + j) = ((w._1 >> (8 * j)) & 255).toInt
          if (i == n - 1) { write = None; bPending = true }
          else write = Some((a, n, i + 1))
        }
        if (d.io.memReq.valid.peek().litToBoolean && mReady) {
          assert(response.isEmpty)
          localCount += 1
          val a = d.io.memReq.bits.addr.peek().litValue.toInt
          assert(a % 64 == 0 && a + 64 <= local.length)
          val error = localCount == faultLocal
          if (d.io.memReq.bits.write.peek().litToBoolean && !error) {
            val data = d.io.memReq.bits.data.peek().litValue
            val mask = d.io.memReq.bits.mask.peek().litValue
            for (j <- 0 until 64 if mask.testBit(j)) local(a + j) = ((data >> (8 * j)) & 255).toInt
          }
          response = Some((pack(local.slice(a, a + 64).toSeq), error))
        }
        d.clock.step()
      }
      def run(out: Boolean, e: Long, l: Long, bytes: Long, rows: Long = 1,
              es: Long = 0, ls: Long = 0, cause: Int = 0): Unit = {
        burstCount = 0; localCount = 0
        val c = d.io.command.bits
        c.write.poke(out); c.external.poke(e); c.local.poke(l)
        c.shape.rowBytes.poke(bytes); c.shape.rows.poke(rows)
        c.shape.externalStride.poke(es); c.shape.localStride.poke(ls)
        d.io.command.valid.poke(true); d.io.command.ready.expect(true)
        tick(); d.io.command.valid.poke(false)
        var cycles = 0
        while (!d.io.completion.valid.peek().litToBoolean && cycles < 30000) { tick(); cycles += 1 }
        d.io.completion.valid.expect(true); d.io.completion.bits.cause.expect(cause)
        assert(read.isEmpty && write.isEmpty && !bPending && response.isEmpty)
        val result = d.io.completion.bits.bytesCompleted.peek().litValue
        if (cause == 0) assert(result == BigInt(bytes) * rows)
        for (_ <- 0 until 5) {
          d.io.command.ready.expect(false); d.io.busy.expect(true)
          tick(); d.io.completion.valid.expect(true); d.io.completion.bits.bytesCompleted.expect(result)
        }
        d.io.completion.ready.poke(true); tick(); d.io.completion.ready.poke(false)
        d.io.busy.expect(false)
      }
      run(false, 4092, 60, 148, 3, 260, 256)
      for (r <- 0 until 3; i <- 0 until 148) assert(local(60 + r * 256 + i) == ext(4092 + r * 260 + i))
      assert(local(59) == 0xa5 && local(208) == 0xa5)
      run(true, 8188, 60, 148, 3, 300, 256)
      for (r <- 0 until 3; i <- 0 until 148) assert(ext(8188 + r * 300 + i) == local(60 + r * 256 + i))
      for (_ <- 0 until 12) {
        val l = random.nextInt(256) * 4
        val e = 12000 + random.nextInt(256) * 4
        val n = (random.nextInt(80) + 1) * 4
        run(false, e, l, n)
        for (i <- 0 until n) assert(local(l + i) == ext(e + i))
        run(true, e + 4096, l, n)
        for (i <- 0 until n) assert(ext(e + 4096 + i) == local(l + i))
      }
      for ((e, l, n, rows, es, ls) <- Seq(
        (0L, 0L, 0L, 1L, 0L, 0L), (0L, 0L, 4L, 0L, 0L, 0L),
        (2L, 0L, 4L, 1L, 0L, 0L), (0L, 2L, 4L, 1L, 0L, 0L),
        (0L, 0L, 6L, 1L, 0L, 0L), (0L, p.dtcmBytes.toLong, 4L, 1L, 0L, 0L),
        (0xfffffffcL, 0L, 8L, 1L, 0L, 0L),
        (0L, 0L, 4L, 0xffffffffL, 0xfffffffcL, 4L),
        (0L, 0L, 4L, 2L, 0L, 0xfffffffcL))) {
        run(false, e, l, n, rows, es, ls, 12); assert(burstCount == 0 && localCount == 0)
      }
      run(false, 0xfffffffcL, p.dtcmBytes - 4, 4)
      faultRead = 2
      run(false, 4092, 0, 128, cause = 13)
      d.io.status.bytesCompleted.expect(4); d.io.status.partial.expect(true)
      assert(burstCount == 2)
      faultRead = -1; faultWrite = 1
      run(true, 0, 0, 128, cause = 14)
      d.io.status.partial.expect(true); d.io.status.bytesCompleted.expect(0)
      faultWrite = -1; wrongId = true
      run(false, 0, 0, 64, cause = 15)
      d.io.status.partial.expect(false)
      run(true, 0, 0, 64, cause = 15)
      wrongId = false
      if (maximum > 1) { earlyLast = true; run(false, 0, 0, 64, cause = 15); earlyLast = false }
      lateLast = true; run(false, 0, 0, 64, cause = 15); lateLast = false
      faultLocal = 1
      run(false, 0, 0, 64, cause = 14)
      run(true, 0, 0, 64, cause = 13); assert(burstCount == 0)
      faultLocal = -1
      run(false, 0, 0, 64)
    }
  }
}
