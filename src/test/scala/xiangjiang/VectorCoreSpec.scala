package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

/** Independent test assembler for XJ-ISA 1.0. IDs are test/internal operations. */
object VectorEncoding {
  def instruction(id: Int, rd: Int, rs1: Int, rs2: Int, mask: Option[Int] = None): BigInt = {
    require(mask.forall(_ == 0), "mask must be v0")
    if (id == 59) return (BigInt(rs2 & 4095) << 20) | (rs1 << 15) | (1 << 12) | (rd << 7) | 0x0b
    if (id == 60) return (BigInt((rs2 & 4095) >> 5) << 25) | (rd << 20) | (rs1 << 15) |
      (2 << 12) | ((rs2 & 31) << 7) | 0x0b
    val (f6, f3, source) = id match {
      case n if n < 36 => (n / 2, if (n % 2 == 0) 0 else 4, rs1)
      case n if n < 52 => (0x1b + (n - 36) / 2, if (n % 2 == 0) 0 else 4, rs1)
      case 52 | 53 => (0x16, if (id == 52) 4 else 3, rs1)
      case 54 | 55 => (0x17, if (id == 54) 4 else 3, rs1)
      case 56 => (0x23, 0, rs1)
      case 57 => (0x18, 0, rs1)
      case 58 => (0x12 + rs1, 2, 0)
      case 61 => (0x19, 1, rs1)
      case 62 => (0x1a, 1, rs1)
      case _ => (0x3f, 0, rs1)
    }
    (BigInt(f6) << 26) | (if (mask.isEmpty) BigInt(1) << 25 else BigInt(0)) |
      (rs2 << 20) | (source << 15) | (f3 << 12) | (rd << 7) | 0x57
  }
  def packed(lanes: Seq[BigInt]): BigInt = lanes.zipWithIndex.foldLeft(BigInt(0)) {
    case (r, (v, i)) => r | ((v & BigInt("ffffffff", 16)) << (i * 32))
  }
}

class VectorCoreSpec extends AnyFunSuite with ChiselSim {
  test("XJ-V source versions, full-register memory, precise faults and restart") {
    val p = XJParams(nIssue = 2, nXRes = 3, nMemq = 3, generationBits = 1,
      seqBits = 6, bankBytes = 1024, nVRes = 3)
    simulate(new ScalarCore(p)) { d =>
      import Rv32._
      import VectorEncoding._
      val mem = scala.collection.mutable.Map.empty[Long, BigInt]
      var program = Seq.empty[BigInt]
      var response: Option[(Int, BigInt, Boolean)] = None
      var errorAddress: Option[Long] = None
      var cycles = 0
      val requests = scala.collection.mutable.ArrayBuffer.empty[(Long, Boolean)]
      val commits = scala.collection.mutable.ArrayBuffer.empty[Int]
      val writebacks = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      d.io.start.poke(false); d.io.ack.poke(false); d.io.fetchReq.ready.poke(true)
      d.io.fetchResp.valid.poke(false); d.io.fetchResp.bits.pc.poke(0); d.io.fetchResp.bits.inst.poke(0)
      d.io.fetchResp.bits.exception.poke(false); d.io.fetchResp.bits.cause.poke(0); d.io.fetchResp.bits.badAddr.poke(0)
      d.io.dataReq.ready.poke(false); d.io.dataResp.valid.poke(false)
      d.io.dataResp.bits.data.poke(0); d.io.dataResp.bits.error.poke(false)
      d.io.matrixReq.ready.poke(false); d.io.matrixResp.valid.poke(false); d.io.matrixResp.bits.data.poke(0)
      def tick(): Unit = {
        d.io.dataReq.ready.poke(cycles % 4 != 1)
        d.io.dataResp.valid.poke(response.exists(_._1 == 0))
        response.foreach { case (_, data, error) => d.io.dataResp.bits.data.poke(data); d.io.dataResp.bits.error.poke(error) }
        val fetch = if (d.io.fetchReq.valid.peek().litToBoolean) Some(d.io.fetchReq.bits.peek().litValue.toInt) else None
        if (d.io.retire.valid.peek().litToBoolean) commits += d.io.retire.bits.uop.pc.peek().litValue.toInt
        if (d.io.writeback.valid.peek().litToBoolean) writebacks += d.io.writeback.bits.data.peek().litValue
        if (response.exists(_._1 == 0) && d.io.dataResp.ready.peek().litToBoolean) response = None
        if (d.io.dataReq.valid.peek().litToBoolean && d.io.dataReq.ready.peek().litToBoolean) {
          assert(response.isEmpty)
          val a = d.io.dataReq.bits.addr.peek().litValue.toLong
          val store = d.io.dataReq.bits.write.peek().litToBoolean
          assert(a % 4 == 0 && a >= 0 && a + 4 <= p.dtcmBytes)
          d.io.dataReq.bits.mask.expect(15)
          requests += ((a, store))
          val error = errorAddress.contains(a)
          if (store && !error) mem(a) = d.io.dataReq.bits.data.peek().litValue
          response = Some((3, mem.getOrElse(a, BigInt(0)), error))
        }
        d.clock.step(); cycles += 1
        d.io.fetchResp.valid.poke(fetch.isDefined)
        fetch.foreach { pc =>
          assert(pc % 4 == 0 && pc / 4 < program.size)
          d.io.fetchResp.bits.pc.poke(pc); d.io.fetchResp.bits.inst.poke(program(pc / 4))
        }
        response = response.map { case (delay, data, error) => (math.max(0, delay - 1), data, error) }
      }
      def run(words: Seq[BigInt], fault: Int = 0, pc: Int = 0, addr: BigInt = 0): Unit = {
        if (d.io.status.done.peek().litToBoolean || d.io.status.fault.peek().litToBoolean) {
          d.io.ack.poke(true); tick(); d.io.ack.poke(false)
        }
        program = words; requests.clear(); commits.clear(); writebacks.clear()
        d.io.start.poke(true); tick(); d.io.start.poke(false)
        val deadline = cycles + 12000
        while (d.io.status.busy.peek().litToBoolean && cycles < deadline) tick()
        d.io.status.busy.expect(false); d.io.status.fault.expect(fault != 0); d.io.status.done.expect(fault == 0)
        if (fault == 0) assert(commits.toSeq == words.indices.map(_ * 4))
        else {
          d.io.status.faultCause.expect(fault); d.io.status.faultPc.expect(pc); d.io.status.faultAddr.expect(addr)
          assert(commits.forall(_ < pc))
        }
        assert(response.isEmpty)
      }
      for (i <- 0 until 8) { mem(i * 4L) = BigInt(i); mem(64L + i * 4) = BigInt(i % 2) }
      run(Seq(addi(1, 0, 64), addi(2, 0, 128), addi(3, 0, 5),
        instruction(59, 2, 0, 0), instruction(59, 1, 1, 0),
        instruction(37, 0, 0, 1), instruction(1, 2, 3, 2, Some(0)),
        instruction(60, 2, 2, 0), fence, halt))
      val mixed = (0 until 8).map(i => BigInt(if (i % 2 == 0) i + 5 else i))
      d.io.debugVRegs(0).expect(0x55); d.io.debugVRegs(2).expect(packed(mixed))
      assert((0 until 8).map(i => mem(128L + 4 * i)) == mixed)
      assert(requests.count(_._2) == 8)
      // Mask and destination alias uses the old mask, with canonical high bits.
      run(Seq(instruction(37, 0, 3, 2, Some(0)), halt))
      d.io.debugVRegs(0).expect(1)
      // Full signed I/S immediates, with a store source different from imm[4:0].
      run(Seq(addi(4, 0, 192), instruction(60, 2, 4, -32), instruction(59, 3, 4, -32), halt))
      d.io.debugVRegs(3).expect(packed(mixed))
      val before = d.io.debugVRegs(3).peek().litValue
      run(Seq(addi(4, 0, -1), instruction(59, 3, 4, 0), halt), 8, 4, BigInt("ffffffff", 16))
      assert(requests.isEmpty); d.io.debugVRegs(3).expect(before)
      run(Seq(addi(4, 0, -32), instruction(60, 3, 4, 0), halt), 11, 4, BigInt("ffffffe0", 16))
      assert(requests.isEmpty)
      errorAddress = Some(8)
      run(Seq(instruction(59, 3, 0, 0), instruction(60, 3, 2, 0), halt), 9, 0, 0)
      assert(requests.toSeq == Seq(0L -> false, 4L -> false, 8L -> false))
      d.io.debugVRegs(3).expect(before); errorAddress = None
      run(Seq(addi(3, 0, 1)) ++ Seq.fill(80)(instruction(1, 7, 3, 7)) ++ Seq(halt))
      d.io.debugVRegs(7).expect(packed(Seq.fill(8)(BigInt(80))))
      for (bad <- Seq(instruction(63, 7, 0, 0), instruction(56, 7, 0, 0), BigInt(0x07))) {
        run(Seq(bad, instruction(60, 7, 2, 0), halt), 1, 0, bad)
        assert(requests.isEmpty)
      }
      // Independent IALU/IMUL work passes an older iterative BF16 divide.
      // CDB arbitrates all responses, but retirement remains in program order.
      for (i <- 0 until 8) mem(512L + 4 * i) = BigInt(0x4000)
      run(Seq(addi(4, 0, 512), instruction(59, 10, 4, 0), addi(3, 0, 12), halt))
      run(Seq(instruction(28, 12, 10, 10), instruction(1, 11, 3, 11), instruction(5, 13, 3, 7), halt))
      val divided = packed(Seq.fill(8)(BigInt(0x3f80)))
      val added = packed(Seq.fill(8)(BigInt(12)))
      val multiplied = packed(Seq.fill(8)(BigInt(960)))
      assert(writebacks.indexOf(added) >= 0 && writebacks.indexOf(added) < writebacks.indexOf(divided))
      assert(writebacks.indexOf(multiplied) >= 0 && writebacks.indexOf(multiplied) < writebacks.indexOf(divided))
      d.io.debugVRegs(12).expect(divided); d.io.debugVRegs(11).expect(added)
      errorAddress = Some(132)
      run(Seq(instruction(60, 7, 2, 0), addi(14, 0, 99), halt), 11, 0, 128)
      assert(requests.toSeq == Seq(128L -> true, 132L -> true)); assert(mem(128) == 80)
      d.io.debugRegs(14).expect(0)
    }
  }
}
