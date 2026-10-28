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
        val data = get64(address)
        (0 until count).map(i => ((data >> (32 * i)) & wordMask).toInt)
      }

      dut.io.flush.poke(false)
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
      def runTask(op: MatOp.Type, unit: Int, buffer: Int = 0, addr: Long = 0): Unit = {
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
      }

      // INT8 matrix multiply: A is 16x16 in [-3, 3], B is 16x16 in [-2, 2].
      val a = Array.tabulate(16, 16) { (i, j) => ((i * 16 + j) % 7) - 3 }
      val b = Array.tabulate(16, 16) { (k, j) => ((k * 16 + j) % 5) - 2 }
      val c = Array.tabulate(16, 16) { (i, j) =>
        (0 until 16).map(k => a(i)(k) * b(k)(j)).sum
      }
      for (beat <- 0 until 4) {
        val values = (beat * 4 until beat * 4 + 4).flatMap { r =>
          (0 until 16).map(col => a(r)(col))
        }
        put8(beat * 64L, values)
      }
      for (beat <- 0 until 4) {
        val values = (beat * 4 until beat * 4 + 4).flatMap { r =>
          (0 until 16).map(col => b(r)(col))
        }
        put8(256L + beat * 64L, values)
      }

      runTask(MatOp.mload, 1, 0, 0)    // A at DTCM 0
      runTask(MatOp.mload, 1, 1, 256)  // B at DTCM 256
      runTask(MatOp.mmul, 1)
      runTask(MatOp.mwait, 1)
      runTask(MatOp.mstore, 1, 0, 512) // C at DTCM 512

      for (i <- 0 until 16) {
        val row = get32s(512L + i * 64L, 16)
        assert(row == c(i).toSeq, s"INT8 u1 row $i mismatch: $row != ${c(i).toSeq}")
      }

      // u2 is an independent physical INT8 systolic array with the same
      // architectural operation and storage format.
      runTask(MatOp.mload, 2, 0, 0)
      runTask(MatOp.mload, 2, 1, 256)
      runTask(MatOp.mmul, 2)
      runTask(MatOp.mwait, 2)
      runTask(MatOp.mstore, 2, 0, 3072)
      for (i <- 0 until 16) {
        val row = get32s(3072L + i * 64L, 16)
        assert(row == c(i).toSeq, s"INT8 u2 row $i mismatch: $row != ${c(i).toSeq}")
      }

      // BF16 MMUL/MMACC round products to BF16, then accumulate in FP32.
      // Diagonal matrices also exercise the final PE's product drain.
      val bfA = Array.tabulate(16, 16) { (i, j) => if (i == j) 0x3f80 else 0 }
      val bfB = Array.tabulate(16, 16) { (i, j) => if (i == j) 0x4000 else 0 }
      for (beat <- 0 until 8) {
        val values = (beat * 2 until beat * 2 + 2).flatMap { r =>
          (0 until 16).map(col => bfA(r)(col))
        }
        put16(0L + beat * 64L, values)
      }
      for (beat <- 0 until 8) {
        val values = (beat * 2 until beat * 2 + 2).flatMap { r =>
          (0 until 16).map(col => bfB(r)(col))
        }
        put16(512L + beat * 64L, values)
      }
      runTask(MatOp.mload, 0, 0, 0)
      runTask(MatOp.mload, 0, 1, 512)
      runTask(MatOp.mmul, 0)
      runTask(MatOp.mwait, 0)
      runTask(MatOp.mstore, 0, 0, 1024)
      for (beat <- 0 until 8) {
        val data = get64(1024L + beat * 64L)
        val got = (0 until 32).map(i => ((data >> (16 * i)) & 0xffff).toInt)
        val expected = (beat * 2 until beat * 2 + 2).flatMap { r =>
          (0 until 16).map(col => if (r == col) 0x4000 else 0)
        }
        assert(got == expected, s"BF16 MMUL beat $beat mismatch: $got != $expected")
      }
      runTask(MatOp.mload, 0, 2, 1024)
      runTask(MatOp.mmacc, 0)
      runTask(MatOp.mwait, 0)
      runTask(MatOp.mstore, 0, 0, 1536)
      for (beat <- 0 until 8) {
        val data = get64(1536L + beat * 64L)
        val got = (0 until 32).map(i => ((data >> (16 * i)) & 0xffff).toInt)
        val expected = (beat * 2 until beat * 2 + 2).flatMap { r =>
          (0 until 16).map(col => if (r == col) 0x4080 else 0)
        }
        assert(got == expected, s"BF16 MMACC beat $beat mismatch: $got != $expected")
      }

      // Non-exact products: BF16(0x3f81)^2 rounds to BF16(0x3f82).
      // Preload -0x3f82 on the diagonal so the new MMACC returns exact zero;
      // the old FP32 multiplication would leave 2^-14 instead. Exercise all
      // diagonal PEs, including the last product in PE(15,15).
      for (beat <- 0 until 8) {
        val input = (beat * 2 until beat * 2 + 2).flatMap { r =>
          (0 until 16).map(col => if (r == col) 0x3f81 else 0)
        }
        val initial = (beat * 2 until beat * 2 + 2).flatMap { r =>
          (0 until 16).map(col => if (r == col) 0xbf82 else 0)
        }
        put16(beat * 64L, input)
        put16(512L + beat * 64L, input)
        put16(1024L + beat * 64L, initial)
      }
      runTask(MatOp.mload, 0, 0, 0)
      runTask(MatOp.mload, 0, 1, 512)
      runTask(MatOp.mload, 0, 2, 1024)
      runTask(MatOp.mmacc, 0)
      runTask(MatOp.mstore, 0, 0, 1536)
      for (beat <- 0 until 8) assert(get64(1536L + beat * 64L) == 0)
      // Consecutive compute tasks preserve MAC order and clear only on MMUL.
      runTask(MatOp.mmul, 0)
      runTask(MatOp.mmacc, 0)
      runTask(MatOp.mstore, 0, 0, 1536)
      for (beat <- 0 until 8) {
        val data = get64(1536L + beat * 64L)
        for (i <- 0 until 32) {
          val e = beat * 32 + i
          val expected = if (e / 16 == e % 16) 0x4002 else 0
          assert(((data >> (16 * i)) & 0xffff) == expected)
        }
      }

      // Dense BF16 matrices exercise all k terms and every spatial PE, not
      // just diagonal multiplication. Small integer operands/sums are exact.
      val denseA = Array.tabulate(16, 16)((i, k) => (i + 2 * k) % 5 - 2)
      val denseB = Array.tabulate(16, 16)((k, j) => (3 * k + j) % 5 - 2)
      def bf16(x: Int): Int = java.lang.Float.floatToRawIntBits(x.toFloat) >>> 16
      for (beat <- 0 until 8) {
        val indices = beat * 32 until (beat + 1) * 32
        put16(beat * 64L, indices.map(e => bf16(denseA(e / 16)(e % 16))))
        put16(512L + beat * 64L, indices.map(e => bf16(denseB(e / 16)(e % 16))))
      }
      runTask(MatOp.mload, 0, 0, 0)
      runTask(MatOp.mload, 0, 1, 512)
      for (pass <- 1 to 2) {
        runTask(if (pass == 1) MatOp.mmul else MatOp.mmacc, 0)
        runTask(MatOp.mstore, 0, 0, 1536)
        for (beat <- 0 until 8; lane <- 0 until 32) {
          val e = beat * 32 + lane
          val sum = (0 until 16).map(k => denseA(e / 16)(k) * denseB(k)(e % 16)).sum
          val got = ((get64(1536L + beat * 64L) >> (16 * lane)) & 0xffff).toInt
          assert(got == bf16(pass * sum), s"dense BF16 pass=$pass PE=${e / 16},${e % 16}")
        }
      }

      // BF16 transpose: exact small integers survive widen/narrow round-trips.
      val t = Array.tabulate(16, 16) { (i, j) => i * 16 + j }
      for (beat <- 0 until 8) {
        val values = (beat * 2 until beat * 2 + 2).flatMap { r =>
          (0 until 16).map(col => t(r)(col))
        }
        put16(0L + beat * 64L, values)
      }
      runTask(MatOp.mload, 0, 0, 0)
      runTask(MatOp.mtrans, 0)
      runTask(MatOp.mwait, 0)
      runTask(MatOp.mstore, 0, 0, 2048)
      for (beat <- 0 until 8) {
        val data = get64(2048L + beat * 64L)
        val got = (0 until 32).map(i => ((data >> (16 * i)) & 0xffff).toInt)
        val expected = (beat * 2 until beat * 2 + 2).flatMap { r =>
          (0 until 16).map(col => t(col)(r))
        }
        assert(got == expected, s"BF16 beat $beat mismatch: $got != $expected")
      }
    }
  }
}
