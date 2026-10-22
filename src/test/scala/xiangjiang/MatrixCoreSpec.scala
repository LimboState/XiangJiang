package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

/** Test-side matrix encodings from Matrix_ISA.md. */
object MatrixEncoding {
  def msetmode(unit: Int, mode: Int): BigInt =
    ((mode & 1) << 20) | ((unit & 3) << 12) | 0x2b
  def mload(unit: Int, buffer: Int, rsA: Int): BigInt =
    (BigInt(4 + (buffer & 3)) << 25) | ((rsA & 31) << 15) | ((unit & 3) << 12) | 0x2b
  def mstore(unit: Int, rsC: Int): BigInt =
    (BigInt(0x08) << 25) | ((rsC & 31) << 15) | ((unit & 3) << 12) | 0x2b
  def mmul(unit: Int): BigInt = ((unit & 3) << 12) | 0x5b
  def mmacc(unit: Int): BigInt = (BigInt(1) << 25) | ((unit & 3) << 12) | 0x5b
  def mew(unit: Int): BigInt = (BigInt(2) << 25) | ((unit & 3) << 12) | 0x5b
  def mtrans(unit: Int): BigInt = (BigInt(3) << 25) | ((unit & 3) << 12) | 0x5b
  def mwait(unit: Int): BigInt = (BigInt(unit & 3) << 30) | 0x7b
  def mfence: BigInt = (BigInt(1) << 12) | 0x7b
  def mbarrier(id: Int): BigInt = (BigInt(id & 31) << 27) | (BigInt(2) << 12) | 0x7b
}

class MatrixCoreSpec extends AnyFunSuite with ChiselSim {
  private val wordMask = (BigInt(1) << 32) - 1

  test("matrix programs decode, order, compute and store complete tiles through DTCM") {
    val p = XJParams(nRob = 8, nIssue = 2, nXRes = 3, nMemq = 3,
      generationBits = 1, seqBits = 6, bankBytes = 1024, nVRes = 2)
    simulate(new ScalarCore(p)) { dut =>
      import Rv32._
      import MatrixEncoding._
      val bytes = scala.collection.mutable.Map.empty[Long, Int]
      def put8(address: Long, values: Seq[Int]): Unit = {
        var data = BigInt(0)
        for ((x, i) <- values.zipWithIndex) data |= (BigInt(x & 0xff) << (8 * i))
        for (i <- 0 until 64) bytes(address + i) = ((data >> (8 * i)) & 255).toInt
      }
      def get64(address: Long): BigInt = (0 until 64).foldLeft(BigInt(0)) { (v, i) =>
        v | (BigInt(bytes.getOrElse(address + i, 0)) << (8 * i))
      }
      def get32s(address: Long, count: Int): Seq[Int] = {
        val data = get64(address)
        (0 until count).map(i => ((data >> (32 * i)) & wordMask).toInt)
      }
      var program = Seq.empty[BigInt]
      var cycles = 0
      var matrixResponse: Option[BigInt] = None
      var scalarResponse: Option[(Int, BigInt, Boolean)] = None
      val requests = scala.collection.mutable.ArrayBuffer.empty[(Long, Boolean, BigInt, Int)]
      val commits = scala.collection.mutable.ArrayBuffer.empty[Int]

      dut.io.start.poke(false); dut.io.ack.poke(false)
      dut.io.fetchReq.ready.poke(true); dut.io.fetchResp.valid.poke(false)
      dut.io.fetchResp.bits.pc.poke(0); dut.io.fetchResp.bits.inst.poke(0)
      dut.io.fetchResp.bits.exception.poke(false); dut.io.fetchResp.bits.cause.poke(0)
      dut.io.fetchResp.bits.badAddr.poke(0)
      dut.io.dataReq.ready.poke(false); dut.io.dataResp.valid.poke(false)
      dut.io.dataResp.bits.data.poke(0); dut.io.dataResp.bits.error.poke(false)
      dut.io.matrixReq.ready.poke(true); dut.io.matrixResp.valid.poke(false)
      dut.io.matrixResp.bits.data.poke(0)

      def tick(): Unit = {
        dut.io.dataResp.valid.poke(scalarResponse.exists(_._1 == 0))
        scalarResponse.foreach { case (_, data, error) =>
          dut.io.dataResp.bits.data.poke(data); dut.io.dataResp.bits.error.poke(error)
        }
        dut.io.matrixResp.valid.poke(matrixResponse.isDefined)
        dut.io.matrixResp.bits.data.poke(matrixResponse.getOrElse(BigInt(0)))

        val fetch = if (dut.io.fetchReq.valid.peek().litToBoolean)
          Some(dut.io.fetchReq.bits.peek().litValue.toInt) else None
        if (dut.io.retire.valid.peek().litToBoolean)
          commits += dut.io.retire.bits.uop.pc.peek().litValue.toInt

        // Scalar word requests (unused by the pure matrix program).
        if (scalarResponse.exists(_._1 == 0) && dut.io.dataResp.ready.peek().litToBoolean)
          scalarResponse = None
        if (dut.io.dataReq.valid.peek().litToBoolean && dut.io.dataReq.ready.peek().litToBoolean) {
          assert(scalarResponse.isEmpty, "multiple active scalar SRAM transactions")
          val addr = dut.io.dataReq.bits.addr.peek().litValue.toLong
          val store = dut.io.dataReq.bits.write.peek().litToBoolean
          val data = dut.io.dataReq.bits.data.peek().litValue
          val mask = dut.io.dataReq.bits.mask.peek().litValue.toInt
          requests += ((addr, store, data, mask))
          if (store)
            for (i <- 0 until 4 if ((mask >> i) & 1) != 0)
              bytes(addr + i) = ((data >> (i * 8)) & 255).toInt
          val read = (0 until 4).foldLeft(BigInt(0)) { (v, i) =>
            v | (BigInt(bytes.getOrElse(addr + i, 0)) << (8 * i))
          }
          scalarResponse = Some((3, read, false))
        }

        // Matrix 64-byte beats.
        val mValid = dut.io.matrixReq.valid.peek().litToBoolean
        val mReady = dut.io.matrixReq.ready.peek().litToBoolean
        if (mValid && mReady) {
          val addr = dut.io.matrixReq.bits.addr.peek().litValue.toLong
          val write = dut.io.matrixReq.bits.write.peek().litToBoolean
          val data = dut.io.matrixReq.bits.data.peek().litValue
          assert(addr >= 0 && addr % 64 == 0 && addr + 64 <= p.dtcmBytes)
          if (write) {
            for (i <- 0 until 64) bytes(addr + i) = ((data >> (8 * i)) & 255).toInt
            matrixResponse = Some(BigInt(0))
          } else {
            matrixResponse = Some(get64(addr))
          }
        }
        if (matrixResponse.isDefined && dut.io.matrixResp.valid.peek().litToBoolean &&
          dut.io.matrixResp.ready.peek().litToBoolean) {
          matrixResponse = None
        }

        dut.clock.step()
        cycles += 1

        dut.io.fetchResp.valid.poke(fetch.isDefined)
        fetch.foreach { pc =>
          assert(pc >= 0 && pc % 4 == 0 && pc / 4 < program.size, s"unexpected fetch $pc")
          dut.io.fetchResp.bits.pc.poke(pc); dut.io.fetchResp.bits.inst.poke(program(pc / 4))
        }
        scalarResponse = scalarResponse.map { case (delay, data, error) =>
          (math.max(0, delay - 1), data, error)
        }
      }

      def run(words: Seq[BigInt], fault: Int = 0): Unit = {
        if (dut.io.status.done.peek().litToBoolean || dut.io.status.fault.peek().litToBoolean) {
          dut.io.ack.poke(true); tick(); dut.io.ack.poke(false)
        }
        program = words; requests.clear(); commits.clear()
        dut.io.start.poke(true); tick(); dut.io.start.poke(false)
        val deadline = cycles + 40000
        while (dut.io.status.busy.peek().litToBoolean && cycles < deadline) tick()
        dut.io.status.busy.expect(false)
        dut.io.status.fault.expect(fault != 0)
        dut.io.status.done.expect(fault == 0)
        if (fault == 0)
          assert(commits.toSeq == words.indices.map(_ * 4), s"retirement PCs $commits")
        assert(scalarResponse.isEmpty)
        assert(matrixResponse.isEmpty)
      }

      // INT8 matrix multiply: A is 16x16 in [-3, 3], B is 16x16 in [-2, 2].
      val a = Array.tabulate(16, 16) { (i, j) => ((i * 16 + j) % 7) - 3 }
      val b = Array.tabulate(16, 16) { (k, j) => ((k * 16 + j) % 5) - 2 }
      val c = Array.tabulate(16, 16) { (i, j) =>
        (0 until 16).map(k => a(i)(k) * b(k)(j)).sum
      }
      for (beat <- 0 until 4) {
        put8(beat * 64L, (beat * 4 until beat * 4 + 4).flatMap { r =>
          (0 until 16).map(col => a(r)(col))
        })
      }
      for (beat <- 0 until 4) {
        put8(256L + beat * 64L, (beat * 4 until beat * 4 + 4).flatMap { r =>
          (0 until 16).map(col => b(r)(col))
        })
      }

      run(Seq(
        addi(1, 0, 0), addi(2, 0, 256), addi(3, 0, 512),
        msetmode(1, 0),
        mload(1, 0, 1),
        mload(1, 1, 2),
        mmul(1),
        mwait(1),
        mstore(1, 3),
        mfence,
        halt))

      for (i <- 0 until 16) {
        val row = get32s(512L + i * 64L, 16)
        assert(row == c(i).toSeq, s"INT8 row $i mismatch: $row != ${c(i).toSeq}")
      }

      // A second run reuses the loaded mode and produces a fresh result.
      run(Seq(
        addi(1, 0, 0), addi(2, 0, 256), addi(3, 0, 1024),
        mload(1, 0, 1),
        mload(1, 1, 2),
        mmul(1),
        mwait(1),
        mstore(1, 3),
        mfence,
        halt))
      for (i <- 0 until 16) {
        val row = get32s(1024L + i * 64L, 16)
        assert(row == c(i).toSeq, s"second INT8 row $i mismatch: $row != ${c(i).toSeq}")
      }
    }
  }
}
