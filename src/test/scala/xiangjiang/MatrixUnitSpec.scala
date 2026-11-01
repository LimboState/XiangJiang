package xiangjiang

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.fu.MatrixUnit

/** Drives MatrixUnit directly with a small byte-addressable DTCM model.
  * Exercises the committed-MASF / staging split: completed tasks must be
  * published by the (test-side) commit path before readers can start.
  */
class MatrixUnitSpec extends AnyFunSuite with ChiselSim {
  private val wordMask = (BigInt(1) << 32) - 1

  test("matrix loads, integer multiply, transpose and stores move complete 8x8 tiles") {
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
        val data = get64(address)
        (0 until count).map(i => ((data >> (32 * i)) & wordMask).toInt)
      }

      dut.io.flush.poke(false)
      dut.io.publish.valid.poke(false)
      dut.io.publish.bits.tag.slot.poke(0)
      dut.io.publish.bits.tag.generation.poke(0)
      dut.io.publish.bits.op.poke(MatOp.mmul)
      dut.io.publish.bits.unit.poke(0)
      dut.io.publish.bits.buffer.poke(0)
      dut.io.publish.bits.mode.poke(false)
      dut.io.task.valid.poke(false)
      dut.io.task.bits.tag.slot.poke(0)
      dut.io.task.bits.tag.generation.poke(0)
      dut.io.task.bits.op.poke(MatOp.mmul)
      dut.io.task.bits.unit.poke(0)
      dut.io.task.bits.buffer.poke(0)
      dut.io.task.bits.addr.poke(0)
      dut.io.memReq.ready.poke(true)
      dut.io.memResp.valid.poke(false)
      dut.io.memResp.bits.data.poke(0)
      dut.clock.step()

      var cycles = 0
      var memResponse: Option[BigInt] = None
      var tagCounter = 0
      def tick(): Unit = {
        dut.io.memResp.valid.poke(memResponse.isDefined)
        dut.io.memResp.bits.data.poke(memResponse.getOrElse(BigInt(0)))
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
        var seen = false
        while (!seen && cycles < deadline) {
          tick()
          seen = dut.io.done.peek().litToBoolean
        }
        assert(seen, s"matrix task did not complete within deadline at cycle $cycles")
      }
      def runTask(op: MatOp.Type, unit: Int, buffer: Int = 0, addr: Long = 0,
                  tag: Int = -1): Int = {
        val t = if (tag >= 0) tag else { tagCounter += 1; tagCounter }
        dut.io.task.bits.op.poke(op)
        dut.io.task.bits.unit.poke(unit)
        dut.io.task.bits.buffer.poke(buffer)
        dut.io.task.bits.addr.poke(addr)
        dut.io.task.bits.tag.slot.poke(t % p.nRob)
        dut.io.task.bits.tag.generation.poke(t / p.nRob)
        dut.io.task.valid.poke(true)
        val deadline = cycles + 200
        while (!dut.io.task.ready.peek().litToBoolean && cycles < deadline) tick()
        assert(dut.io.task.ready.peek().litToBoolean,
          s"MatrixUnit did not accept task at cycle $cycles (scoreboard blocked?)")
        tick()
        dut.io.task.valid.poke(false)
        waitDone()
        t
      }
      def publish(tag: Int, op: MatOp.Type, unit: Int = 0, buffer: Int = 0,
                  mode: Boolean = false): Unit = {
        dut.io.publish.valid.poke(true)
        dut.io.publish.bits.tag.slot.poke(tag % p.nRob)
        dut.io.publish.bits.tag.generation.poke(tag / p.nRob)
        dut.io.publish.bits.op.poke(op)
        dut.io.publish.bits.unit.poke(unit)
        dut.io.publish.bits.buffer.poke(buffer)
        dut.io.publish.bits.mode.poke(mode)
        tick()
        dut.io.publish.valid.poke(false)
      }

      // INT8 8x8 matrix multiply: A in [-3, 3], B in [-2, 2].
      val a = Array.tabulate(8, 8) { (i, j) => ((i * 8 + j) % 7) - 3 }
      val b = Array.tabulate(8, 8) { (k, j) => ((k * 8 + j) % 5) - 2 }
      val c = Array.tabulate(8, 8) { (i, j) =>
        (0 until 8).map(k => a(i)(k) * b(k)(j)).sum
      }
      put8(0L, a.flatten.toSeq.map(_ & 0xff))
      put8(64L, b.flatten.toSeq.map(_ & 0xff))

      val tA = runTask(MatOp.mload, 1, 0, 0)
      publish(tA, MatOp.mload, 1, 0)
      val tB = runTask(MatOp.mload, 1, 1, 64)
      publish(tB, MatOp.mload, 1, 1)
      val tM = runTask(MatOp.mmul, 1)
      publish(tM, MatOp.mmul, 1)
      runTask(MatOp.mstore, 1, 0, 128) // C at DTCM 128: 4 beats x 16 x INT32
      for (i <- 0 until 8) {
        val beat = i / 2
        val local = i % 2
        val row = get32s(128L + beat * 64L + local * 32L, 8)
        assert(row == c(i).toSeq, s"INT8 u1 row $i mismatch: $row != ${c(i).toSeq}")
      }

      // u2 is an independent physical INT8 systolic array with the same
      // architectural operation and storage format.
      val t2A = runTask(MatOp.mload, 2, 0, 0)
      publish(t2A, MatOp.mload, 2, 0)
      val t2B = runTask(MatOp.mload, 2, 1, 64)
      publish(t2B, MatOp.mload, 2, 1)
      val t2M = runTask(MatOp.mmul, 2)
      publish(t2M, MatOp.mmul, 2)
      runTask(MatOp.mstore, 2, 0, 384)
      for (i <- 0 until 8) {
        val beat = i / 2
        val local = i % 2
        val row = get32s(384L + beat * 64L + local * 32L, 8)
        assert(row == c(i).toSeq, s"INT8 u2 row $i mismatch: $row != ${c(i).toSeq}")
      }

      // BF16 MMUL/MMACC round products to BF16, then accumulate in FP32.
      // Diagonal matrices also exercise the final PE's product drain.
      val bfA = Array.tabulate(8, 8) { (i, j) => if (i == j) 0x3f80 else 0 }
      val bfB = Array.tabulate(8, 8) { (i, j) => if (i == j) 0x4000 else 0 }
      for (beat <- 0 until 2) {
        val values = (beat * 4 until beat * 4 + 4).flatMap { r =>
          (0 until 8).map(col => bfA(r)(col))
        }
        put16(0L + beat * 64L, values)
      }
      for (beat <- 0 until 2) {
        val values = (beat * 4 until beat * 4 + 4).flatMap { r =>
          (0 until 8).map(col => bfB(r)(col))
        }
        put16(128L + beat * 64L, values)
      }
      val bA = runTask(MatOp.mload, 0, 0, 0)
      publish(bA, MatOp.mload, 0, 0)
      val bB = runTask(MatOp.mload, 0, 1, 128)
      publish(bB, MatOp.mload, 0, 1)
      val bM = runTask(MatOp.mmul, 0)
      publish(bM, MatOp.mmul, 0)
      runTask(MatOp.mstore, 0, 0, 256)
      for (beat <- 0 until 2) {
        val data = get64(256L + beat * 64L)
        val got = (0 until 32).map(i => ((data >> (16 * i)) & 0xffff).toInt)
        val expected = (beat * 4 until beat * 4 + 4).flatMap { r =>
          (0 until 8).map(col => if (r == col) 0x4000 else 0)
        }
        assert(got == expected, s"BF16 MMUL beat $beat mismatch: $got != $expected")
      }
      val bL = runTask(MatOp.mload, 0, 2, 256)
      publish(bL, MatOp.mload, 0, 2)
      val bMa = runTask(MatOp.mmacc, 0)
      publish(bMa, MatOp.mmacc, 0)
      runTask(MatOp.mstore, 0, 0, 384)
      for (beat <- 0 until 2) {
        val data = get64(384L + beat * 64L)
        val got = (0 until 32).map(i => ((data >> (16 * i)) & 0xffff).toInt)
        val expected = (beat * 4 until beat * 4 + 4).flatMap { r =>
          (0 until 8).map(col => if (r == col) 0x4080 else 0)
        }
        assert(got == expected, s"BF16 MMACC beat $beat mismatch: $got != $expected")
      }

      // Non-exact products: BF16(0x3f81)^2 rounds to BF16(0x3f82).
      // Preload -0x3f82 on the diagonal so the new MMACC returns exact zero.
      for (beat <- 0 until 2) {
        val input = (beat * 4 until beat * 4 + 4).flatMap { r =>
          (0 until 8).map(col => if (r == col) 0x3f81 else 0)
        }
        val initial = (beat * 4 until beat * 4 + 4).flatMap { r =>
          (0 until 8).map(col => if (r == col) 0xbf82 else 0)
        }
        put16(beat * 64L, input)
        put16(128L + beat * 64L, input)
        put16(256L + beat * 64L, initial)
      }
      val dA = runTask(MatOp.mload, 0, 0, 0)
      publish(dA, MatOp.mload, 0, 0)
      val dB = runTask(MatOp.mload, 0, 1, 128)
      publish(dB, MatOp.mload, 0, 1)
      val dL = runTask(MatOp.mload, 0, 2, 256)
      publish(dL, MatOp.mload, 0, 2)
      val dM = runTask(MatOp.mmacc, 0)
      publish(dM, MatOp.mmacc, 0)
      runTask(MatOp.mstore, 0, 0, 384)
      for (beat <- 0 until 2) {
        val v = get64(384L + beat * 64L)
        assert(v == 0)
      }

      // Dense BF16 matrices exercise all k terms and every spatial PE.
      val denseA = Array.tabulate(8, 8)((i, k) => (i + 2 * k) % 5 - 2)
      val denseB = Array.tabulate(8, 8)((k, j) => (3 * k + j) % 5 - 2)
      def bf16(x: Int): Int = java.lang.Float.floatToRawIntBits(x.toFloat) >>> 16
      for (beat <- 0 until 2) {
        val indices = beat * 32 until (beat + 1) * 32
        put16(beat * 64L, indices.map(e => bf16(denseA(e / 8)(e % 8))))
        put16(128L + beat * 64L, indices.map(e => bf16(denseB(e / 8)(e % 8))))
      }
      val eA = runTask(MatOp.mload, 0, 0, 0)
      publish(eA, MatOp.mload, 0, 0)
      val eB = runTask(MatOp.mload, 0, 1, 128)
      publish(eB, MatOp.mload, 0, 1)
      for (pass <- 1 to 2) {
        val t = runTask(if (pass == 1) MatOp.mmul else MatOp.mmacc, 0)
        publish(t, if (pass == 1) MatOp.mmul else MatOp.mmacc, 0)
        runTask(MatOp.mstore, 0, 0, 384)
        for (beat <- 0 until 2; lane <- 0 until 32) {
          val e = beat * 32 + lane
          val sum = (0 until 8).map(k => denseA(e / 8)(k) * denseB(k)(e % 8)).sum
          val got = ((get64(384L + beat * 64L) >> (16 * lane)) & 0xffff).toInt
          assert(got == bf16(pass * sum), s"dense BF16 pass=$pass PE=${e / 8},${e % 8}")
        }
      }

      // BF16 transpose: exact small integers survive widen/narrow round-trips.
      val t = Array.tabulate(8, 8) { (i, j) => i * 8 + j }
      for (beat <- 0 until 2) {
        val values = (beat * 4 until beat * 4 + 4).flatMap { r =>
          (0 until 8).map(col => t(r)(col))
        }
        put16(0L + beat * 64L, values)
      }
      val fA = runTask(MatOp.mload, 0, 0, 0)
      publish(fA, MatOp.mload, 0, 0)
      val fT = runTask(MatOp.mtrans, 0)
      publish(fT, MatOp.mtrans, 0)
      runTask(MatOp.mstore, 0, 0, 448)
      for (beat <- 0 until 2) {
        val data = get64(448L + beat * 64L)
        val got = (0 until 32).map(i => ((data >> (16 * i)) & 0xffff).toInt)
        val expected = (beat * 4 until beat * 4 + 4).flatMap { r =>
          (0 until 8).map(col => t(col)(r))
        }
        assert(got == expected, s"BF16 mtrans beat $beat mismatch: $got != $expected")
      }

      // INT8 MEW: elementwise signed products sign-extended to INT32.
      val mewA = Array.tabulate(8, 8) { (i, j) => i * 8 + j - 30 }
      val mewB = Array.tabulate(8, 8) { (i, j) => j - 3 }
      put8(0L, mewA.flatten.toSeq.map(_ & 0xff))
      put8(64L, mewB.flatten.toSeq.map(_ & 0xff))
      val gA = runTask(MatOp.mload, 1, 0, 0)
      publish(gA, MatOp.mload, 1, 0)
      val gB = runTask(MatOp.mload, 1, 1, 64)
      publish(gB, MatOp.mload, 1, 1)
      val gM = runTask(MatOp.mew, 1)
      publish(gM, MatOp.mew, 1)
      runTask(MatOp.mstore, 1, 0, 512)
      for (i <- 0 until 8) {
        val beat = i / 2
        val local = i % 2
        val row = get32s(512L + beat * 64L + local * 32L, 8)
        val expected = (0 until 8).map(j => mewA(i)(j) * mewB(i)(j))
        assert(row == expected, s"INT8 MEW row $i mismatch: $row != $expected")
      }

      // Flush discards uncommitted staging but preserves committed MASF.
      put8(0L, Seq.fill(64)(7))
      val hA = runTask(MatOp.mload, 1, 0, 0) // completes, not published
      dut.io.flush.poke(true)
      tick()
      dut.io.flush.poke(false)
      // MSTORE reads committed ACC only, but a fresh MMUL must read committed
      // A (from the earlier gA publish), not the discarded staging.
      val hM = runTask(MatOp.mmul, 1)
      publish(hM, MatOp.mmul, 1)
      runTask(MatOp.mstore, 1, 0, 512)
      // Committed A for unit 1 is mewA; B is mewB: C = mewA x mewB.
      for (i <- 0 until 8) {
        val beat = i / 2
        val local = i % 2
        val row = get32s(512L + beat * 64L + local * 32L, 8)
        val expected = (0 until 8).map { j =>
          (0 until 8).map(k => mewA(i)(k) * mewB(k)(j)).sum
        }
        assert(row == expected, s"post-flush INT8 row $i mismatch: $row != $expected")
      }
    }
  }

  test("scoreboard blocks readers until the older writer commits") {
    val p = XJParams(bankBytes = 1024)
    simulate(new MatrixUnit(p)) { dut =>
      dut.io.flush.poke(false)
      dut.io.publish.valid.poke(false)
      dut.io.task.valid.poke(false)
      dut.io.task.bits.op.poke(MatOp.mload)
      dut.io.task.bits.unit.poke(1)
      dut.io.task.bits.buffer.poke(0)
      dut.io.task.bits.addr.poke(0)
      dut.io.memReq.ready.poke(true)
      dut.io.memResp.valid.poke(false)
      dut.clock.step()

      var cycles = 0
      var memResponse: Option[BigInt] = None
      def tick(): Unit = {
        dut.io.memResp.valid.poke(memResponse.isDefined)
        if (memResponse.isDefined && dut.io.memResp.ready.peek().litToBoolean) {
          memResponse = None
        }
        if (dut.io.memReq.valid.peek().litToBoolean && dut.io.memReq.ready.peek().litToBoolean) {
          memResponse = Some(BigInt(0))
        }
        dut.clock.step()
        cycles += 1
      }
      def untilDone(limit: Int): Unit = {
        var seen = false
        val deadline = cycles + limit
        while (!seen && cycles < deadline) {
          tick()
          seen = dut.io.done.peek().litToBoolean
        }
        assert(seen, "task did not complete")
      }
      def startTask(tag: Int): Unit = {
        dut.io.task.bits.tag.slot.poke(tag % p.nRob)
        dut.io.task.bits.tag.generation.poke(tag / p.nRob)
        dut.io.task.valid.poke(true)
        val deadline = cycles + 200
        while (!dut.io.task.ready.peek().litToBoolean && cycles < deadline) tick()
        assert(dut.io.task.ready.peek().litToBoolean, "task not accepted")
        tick()
        dut.io.task.valid.poke(false)
        untilDone(500)
      }
      // First A load completes but is not published.
      startTask(0)
      // A second A load (a writer to the same resource) must wait for the
      // first writer's commit.
      dut.io.task.bits.tag.slot.poke(1 % p.nRob)
      dut.io.task.bits.tag.generation.poke(1 / p.nRob)
      dut.io.task.valid.poke(true)
      for (_ <- 0 until 5) tick()
      assert(!dut.io.task.ready.peek().litToBoolean, "second writer started before first committed")
      // Publish the first writer; the second task can now start.
      dut.io.publish.valid.poke(true)
      dut.io.publish.bits.tag.slot.poke(0)
      dut.io.publish.bits.tag.generation.poke(0)
      dut.io.publish.bits.op.poke(MatOp.mload)
      dut.io.publish.bits.unit.poke(1)
      dut.io.publish.bits.buffer.poke(0)
      tick()
      dut.io.publish.valid.poke(false)
      val deadline = cycles + 200
      while (!dut.io.task.ready.peek().litToBoolean && cycles < deadline) tick()
      assert(dut.io.task.ready.peek().litToBoolean, "second writer blocked after commit")
      tick()
      dut.io.task.valid.poke(false)
      untilDone(500)
      // MMUL reads A and B; the uncommitted second A writer must block it.
      dut.io.task.bits.op.poke(MatOp.mmul)
      dut.io.task.bits.tag.slot.poke(2 % p.nRob)
      dut.io.task.bits.tag.generation.poke(2 / p.nRob)
      dut.io.task.valid.poke(true)
      for (_ <- 0 until 5) tick()
      assert(!dut.io.task.ready.peek().litToBoolean, "MMUL read uncommitted A")
      dut.io.publish.valid.poke(true)
      dut.io.publish.bits.tag.slot.poke(1 % p.nRob)
      dut.io.publish.bits.tag.generation.poke(1 / p.nRob)
      dut.io.publish.bits.op.poke(MatOp.mload)
      dut.io.publish.bits.buffer.poke(0)
      tick()
      dut.io.publish.valid.poke(false)
      val deadline2 = cycles + 200
      while (!dut.io.task.ready.peek().litToBoolean && cycles < deadline2) tick()
      assert(dut.io.task.ready.peek().litToBoolean, "MMUL blocked after commit")
      tick()
      dut.io.task.valid.poke(false)
      untilDone(500)
    }
  }
}
