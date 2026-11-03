package xiangjiang

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.fu.MatrixUnit

/** Drives MatrixUnit directly with a small byte-addressable DTCM model. */
class MatrixUnitSpec extends AnyFunSuite with ChiselSim {
  private val wordMask = (BigInt(1) << 32) - 1

  test("matrix loads, integer multiply, transpose and stores move complete tiles") {
    val p = XJParams(bankBytes = 1024)
    simulate(new MatrixUnit(p)) { dut =>
      val bytes = scala.collection.mutable.Map.empty[Long, Int]
      def put64(address: Long, data: BigInt): Unit =
        for (i <- 0 until 64) bytes(address + i) = ((data >> (8 * i)) & 255).toInt
      def get64(address: Long): BigInt = (0 until 64).foldLeft(BigInt(0)) { (v, i) =>
        v | (BigInt(bytes.getOrElse(address + i, 0)) << (8 * i))
      }
      def put8(address: Long, values: Seq[Int]): Unit = {
        var data = BigInt(0)
        for ((x, i) <- values.zipWithIndex) data |= (BigInt(x & 0xff) << (8 * i))
        put64(address, data)
      }
      def put16(address: Long, values: Seq[Int]): Unit = {
        var data = BigInt(0)
        for ((x, i) <- values.zipWithIndex) data |= (BigInt(x & 0xffff) << (16 * i))
        put64(address, data)
      }
      def get32s(address: Long, count: Int): Seq[Int] = {
        (0 until count).map(i => (get64(address + i * 4) & wordMask).toInt)
      }

      dut.io.flush.poke(false)
      dut.io.commit.valid.poke(false)
      dut.io.commit.bits.slot.poke(0)
      dut.io.commit.bits.generation.poke(0)
      dut.io.task.bits.tag.slot.poke(0)
      dut.io.task.bits.tag.generation.poke(0)
      dut.io.config.valid.poke(false)
      dut.io.config.bits.unit.poke(0)
      dut.io.config.bits.mode.poke(false)
      dut.io.task.valid.poke(false)
      dut.io.task.bits.op.poke(MatOp.mwait)
      dut.io.task.bits.unit.poke(0)
      dut.io.task.bits.buffer.poke(0)
      dut.io.task.bits.addr.poke(0)
      dut.io.memReq.ready.poke(true)
      dut.io.memResp.valid.poke(false)
      dut.io.memResp.bits.data.poke(0)
      dut.clock.step()

      var cycles = 0
      var memResponse: Option[BigInt] = None
      var failResponse = false
      def tick(): Unit = {
        dut.io.memResp.valid.poke(memResponse.isDefined)
        dut.io.memResp.bits.data.poke(memResponse.getOrElse(BigInt(0)))
        dut.io.memResp.bits.error.poke(failResponse)
        val reqValid = dut.io.memReq.valid.peek().litToBoolean
        val reqReady = dut.io.memReq.ready.peek().litToBoolean
        if (reqValid && reqReady) {
          val addr = dut.io.memReq.bits.addr.peek().litValue.toLong
          val write = dut.io.memReq.bits.write.peek().litToBoolean
          val data = dut.io.memReq.bits.data.peek().litValue
          assert(addr >= 0 && addr % 64 == 0 && addr + 64 <= p.dtcmBytes)
          if (write) put64(addr, data) else memResponse = Some(get64(addr))
          if (write) memResponse = Some(BigInt(0))
        }
        val respReady = dut.io.memResp.ready.peek().litToBoolean
        if (memResponse.isDefined && dut.io.memResp.valid.peek().litToBoolean && respReady) {
          memResponse = None
        }
        dut.clock.step()
        cycles += 1
      }
      def waitDone(): Unit = {
        val deadline = cycles + 5000
        var seen = dut.io.done.peek().litToBoolean
        while (!seen && cycles < deadline) {
          tick()
          seen = dut.io.done.peek().litToBoolean
        }
        assert(seen, s"matrix task did not complete within deadline at cycle $cycles")
      }
      def runTask(op: MatOp.Type, unit: Int, buffer: Int = 0, addr: Long = 0, accessError: Boolean = false): Unit = {
        dut.io.task.bits.op.poke(op)
        dut.io.task.bits.unit.poke(unit)
        dut.io.task.bits.buffer.poke(buffer)
        dut.io.task.bits.addr.poke(addr)
        dut.io.task.valid.poke(true)
        val deadline = cycles + 10
        while (!dut.io.task.ready.peek().litToBoolean && cycles < deadline) tick()
        assert(dut.io.task.ready.peek().litToBoolean, "MatrixUnit did not accept task")
        tick()
        dut.io.task.valid.poke(false)
        waitDone()
        dut.io.stateFault.expect(false)
        dut.io.accessFault.expect(accessError)
        tick() // completed -> awaitCommit; a stale owner may not publish/release.
        dut.io.commit.valid.poke(true)
        dut.io.commit.bits.generation.poke(1)
        tick()
        dut.io.task.ready.expect(false)
        dut.io.commit.bits.generation.poke(0)
        tick()
        dut.io.commit.valid.poke(false)
      }

      val a = Array.tabulate(8, 8) { (i, j) => ((i * 8 + j) % 7) - 3 }
      val b = Array.tabulate(8, 8) { (k, j) => ((k * 8 + j) % 5) - 2 }
      val c = Array.tabulate(8, 8) { (i, j) =>
        (0 until 8).map(k => a(i)(k) * b(k)(j)).sum
      }
      put8(0, a.toSeq.flatMap(_.toSeq))
      put8(256, b.toSeq.flatMap(_.toSeq))

      runTask(MatOp.mload, 1, 0, 0)    // A at DTCM 0
      runTask(MatOp.mload, 1, 1, 256)  // B at DTCM 256
      runTask(MatOp.mmul, 1)
      runTask(MatOp.mwait, 1)
      runTask(MatOp.mstore, 1, 0, 512) // C at DTCM 512

      for (i <- 0 until 8) {
        val row = get32s(512L + i * 32L, 8)
        assert(row == c(i).toSeq, s"INT8 row $i mismatch: $row != ${c(i).toSeq}")
      }

      // A failed replacement load must not publish staging even if a matching
      // commit is presented at the standalone unit boundary.
      failResponse = true
      runTask(MatOp.mload, 1, 2, 0, accessError = true)
      failResponse = false
      runTask(MatOp.mstore, 1, 0, 1024)
      for (i <- 0 until 8) assert(get32s(1024L + i * 32L, 8) == c(i).toSeq)

      // WS keeps weights in PE[k,j] rather than output sums in PE[i,j].
      // It must agree bit-for-bit with OS and reuse committed A/B.
      dut.io.config.bits.unit.poke(1); dut.io.config.bits.mode.poke(true)
      dut.io.config.valid.poke(true); tick(); dut.io.config.valid.poke(false)
      runTask(MatOp.mmul, 1)
      runTask(MatOp.mstore, 1, 0, 1024)
      for (i <- 0 until 8) assert(get32s(1024L + i * 32L, 8) == c(i).toSeq)
      runTask(MatOp.mmacc, 1)
      runTask(MatOp.mstore, 1, 0, 1024)
      for (i <- 0 until 8) assert(get32s(1024L + i * 32L, 8) == c(i).map(_ * 2).toSeq)

      // BF16 MMACC starts with old ACC before k=0: (1+2^24)+(-2^24)=0.
      // Computing the dot first and then adding old ACC would incorrectly give 1.
      val fa = Array.fill(64)(0)
      val fb = Array.fill(64)(0)
      fa(0) = 0x4b80 // 2^24
      fa(1) = 0xcb80 // -2^24
      fb(0) = 0x3f80
      fb(8) = 0x3f80
      for (beat <- 0 until 2) {
        put16(beat * 64L, fa.slice(beat * 32, beat * 32 + 32).toSeq)
        put16(256L + beat * 64L, fb.slice(beat * 32, beat * 32 + 32).toSeq)
        put16(1024L + beat * 64L, Seq.fill(32)(0x3f80))
      }
      runTask(MatOp.mload, 0, 0, 0)
      runTask(MatOp.mload, 0, 1, 256)
      runTask(MatOp.mload, 0, 2, 1024)
      runTask(MatOp.mmacc, 0)
      runTask(MatOp.mstore, 0, 0, 2048)
      assert((get64(2048) & 0xffff) == 0) // (1+2^24) rounds, then cancels

      // BF16 transpose preserves subnormal input values.
      val t = Array.tabulate(8, 8) { (i, j) => i * 8 + j }
      for (beat <- 0 until 2) {
        val values = (beat * 4 until beat * 4 + 4).flatMap { r =>
          (0 until 8).map(col => t(r)(col))
        }
        put16(0L + beat * 64L, values)
      }
      runTask(MatOp.mload, 0, 0, 0)
      runTask(MatOp.mtrans, 0)
      runTask(MatOp.mwait, 0)
      runTask(MatOp.mstore, 0, 0, 2048)
      for (beat <- 0 until 2) {
        val data = get64(2048L + beat * 64L)
        val got = (0 until 32).map(i => ((data >> (16 * i)) & 0xffff).toInt)
        val expected = (beat * 4 until beat * 4 + 4).flatMap { r =>
          (0 until 8).map(col => t(col)(r))
        }
        assert(got == expected, s"BF16 beat $beat mismatch: $got != $expected")
      }
    }
  }
}
