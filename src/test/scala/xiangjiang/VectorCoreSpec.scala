package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

/** Test-side machine encoding, independent of decoder implementation. */
object VectorEncoding {
  def instruction(id: Int, rd: Int, rs1: Int, rs2: Int,
                  mask: Option[Int] = None): BigInt = {
    require(id >= 0 && id < 64)
    val opcode = Seq(0x57, 0x07, 0x27, 0x2f)(id & 3)
    (BigInt(mask.getOrElse(0)) << 27) | (if (mask.isEmpty) BigInt(1) << 26 else BigInt(0)) |
      (BigInt(id >> 5) << 25) | ((rs2 & 31) << 20) | (rs1 << 15) |
      (((id >> 2) & 7) << 12) | (rd << 7) | opcode
  }
  def packed(lanes: Seq[BigInt]): BigInt =
    lanes.zipWithIndex.foldLeft(BigInt(0)) { case (r, (v, i)) =>
      r | ((v & BigInt("ffffffff", 16)) << (i * 32))
    }
}

class VectorCoreSpec extends AnyFunSuite with ChiselSim {
  test("vector programs preserve versions, order memory, precheck faults and restart under backpressure") {
    val p = XJParams(nRob = 8, nIssue = 2, nXRes = 3, nMemq = 3,
      generationBits = 1, seqBits = 6, bankBytes = 1024, nVRes = 2)
    simulate(new ScalarCore(p)) { dut =>
      import Rv32._
      import VectorEncoding._
      val bytes = scala.collection.mutable.Map.empty[Long, Int]
      def put32(address: Long, value: BigInt): Unit =
        for (i <- 0 until 4) bytes(address + i) = ((value >> (8 * i)) & 255).toInt
      def get32(address: Long): BigInt = (0 until 4).foldLeft(BigInt(0)) { (v, i) =>
        v | (BigInt(bytes.getOrElse(address + i, 0)) << (8 * i))
      }
      def vectorAt(address: Long): BigInt = packed((0 until 16).map(i => get32(address + 4 * i)))
      var program = Seq.empty[BigInt]
      var cycles = 0
      var response: Option[(Int, BigInt, Boolean)] = None
      var requestDelay = 3
      var errorAddress: Option[Long] = None
      val requests = scala.collection.mutable.ArrayBuffer.empty[(Long, Boolean, BigInt, Int)]
      val commits = scala.collection.mutable.ArrayBuffer.empty[Int]
      var earlyResult = false
      var monitorEarly = false
      var beforeV7 = BigInt(0)
      dut.io.start.poke(false); dut.io.ack.poke(false)
      dut.io.fetchReq.ready.poke(true); dut.io.fetchResp.valid.poke(false)
      dut.io.fetchResp.bits.pc.poke(0); dut.io.fetchResp.bits.inst.poke(0)
      dut.io.fetchResp.bits.exception.poke(false); dut.io.fetchResp.bits.cause.poke(0)
      dut.io.fetchResp.bits.badAddr.poke(0)
      dut.io.dataReq.ready.poke(false); dut.io.dataResp.valid.poke(false)
      dut.io.dataResp.bits.data.poke(0); dut.io.dataResp.bits.error.poke(false)
      def tick(): Unit = {
        dut.io.dataReq.ready.poke(cycles % 4 != 1)
        dut.io.dataResp.valid.poke(response.exists(_._1 == 0))
        response.foreach { case (_, data, error) =>
          dut.io.dataResp.bits.data.poke(data); dut.io.dataResp.bits.error.poke(error)
        }
        val fetch = if (dut.io.fetchReq.valid.peek().litToBoolean)
          Some(dut.io.fetchReq.bits.peek().litValue.toInt) else None
        if (dut.io.retire.valid.peek().litToBoolean)
          commits += dut.io.retire.bits.uop.pc.peek().litValue.toInt
        if (monitorEarly && commits.isEmpty && dut.io.writeback.valid.peek().litToBoolean &&
          dut.io.writeback.bits.data.peek().litValue == packed(Seq.fill(16)(BigInt(12)))) {
          earlyResult = true
          dut.io.debugVRegs(7).expect(beforeV7)
        }
        if (response.exists(_._1 == 0) && dut.io.dataResp.ready.peek().litToBoolean) response = None
        if (dut.io.dataReq.valid.peek().litToBoolean && dut.io.dataReq.ready.peek().litToBoolean) {
          assert(response.isEmpty, "multiple active SRAM transactions")
          val addr = dut.io.dataReq.bits.addr.peek().litValue.toLong
          val store = dut.io.dataReq.bits.write.peek().litToBoolean
          val data = dut.io.dataReq.bits.data.peek().litValue
          val mask = dut.io.dataReq.bits.mask.peek().litValue.toInt
          assert(addr % 4 == 0 && addr >= 0 && addr + 4 <= p.dtcmBytes)
          requests += ((addr, store, data, mask))
          val error = errorAddress.contains(addr)
          if (store && !error)
            for (i <- 0 until 4 if ((mask >> i) & 1) != 0)
              bytes(addr + i) = ((data >> (i * 8)) & 255).toInt
          response = Some((requestDelay, get32(addr), error))
        }
        dut.clock.step(); cycles += 1
        dut.io.fetchResp.valid.poke(fetch.isDefined)
        fetch.foreach { pc =>
          assert(pc >= 0 && pc % 4 == 0 && pc / 4 < program.size, s"unexpected fetch $pc")
          dut.io.fetchResp.bits.pc.poke(pc); dut.io.fetchResp.bits.inst.poke(program(pc / 4))
        }
        response = response.map { case (delay, data, error) => (math.max(0, delay - 1), data, error) }
      }
      def run(words: Seq[BigInt], fault: Int = 0, faultPc: Int = 0, faultAddr: Long = 0): Unit = {
        if (dut.io.status.done.peek().litToBoolean || dut.io.status.fault.peek().litToBoolean) {
          dut.io.ack.poke(true); tick(); dut.io.ack.poke(false)
        }
        program = words; requests.clear(); commits.clear()
        dut.io.start.poke(true); tick(); dut.io.start.poke(false)
        val deadline = cycles + 12000
        while (dut.io.status.busy.peek().litToBoolean && cycles < deadline) tick()
        dut.io.status.busy.expect(false)
        dut.io.status.fault.expect(fault != 0); dut.io.status.done.expect(fault == 0)
        if (fault == 0) assert(commits.toSeq == words.indices.map(_ * 4), s"retirement PCs $commits")
        else {
          dut.io.status.faultCause.expect(fault); dut.io.status.faultPc.expect(faultPc)
          dut.io.status.faultAddr.expect(faultAddr)
          assert(commits.forall(_ < faultPc))
        }
        assert(response.isEmpty)
      }
      for (i <- 0 until 16) { put32(i * 4, i); put32(64 + i * 4, i % 2) }
      run(Seq(addi(1, 0, 64), addi(2, 0, 128), addi(3, 0, 5),
        instruction(59, 0, 0, 0), instruction(59, 1, 1, 0),
        instruction(0, 2, 1, 0), instruction(1, 0, 3, 0),
        instruction(37, 4, 0, 1), instruction(1, 2, 3, 0, Some(4)),
        instruction(60, 2, 2, 0), fence, halt))
      val mixed = (0 until 16).map(i => BigInt(if (i % 2 == 0) i + 10 else i + 1))
      dut.io.debugVRegs(0).expect(packed((0 until 16).map(i => BigInt(i + 5))))
      dut.io.debugVRegs(4).expect(0x5555)
      dut.io.debugVRegs(2).expect(packed(mixed)); assert(vectorAt(128) == packed(mixed))
      assert(requests.count(_._2) == 16)
      assert(requests.map(_._1).toSeq == (0 until 48).map(_ * 4L))
      // A masked comparison aliases its mask and destination: read the old mask first.
      run(Seq(instruction(37, 4, 0, 0, Some(4)), halt))
      dut.io.debugVRegs(4).expect(0)
      // All-masked memory ignores even invalid/misaligned addresses and preserves v0.
      val old0 = dut.io.debugVRegs(0).peek().litValue
      run(Seq(addi(5, 0, -1), instruction(59, 0, 5, 0, Some(4)),
        instruction(60, 0, 5, 0, Some(4)), halt))
      assert(requests.isEmpty); dut.io.debugVRegs(0).expect(old0)
      // Inactive lanes outside DTCM must not fail the one active legal lane.
      put32(p.dtcmBytes - 4, BigInt("87654321", 16))
      run(Seq(addi(10, 0, 1), instruction(1, 4, 10, 4), lui(10, 1),
        instruction(59, 3, 10, -4, Some(4)), halt))
      assert(requests.map(_._1).toSeq == Seq((p.dtcmBytes - 4).toLong))
      dut.io.debugVRegs(3).expect(BigInt("87654321", 16))
      // Packed BF16 load/store crosses 64-byte banks, including both word halves.
      val bf = (1 to 16).map(i => BigInt(java.lang.Float.floatToRawIntBits(i.toFloat) >>> 16))
      for (i <- bf.indices) {
        bytes(510L + i * 2) = (bf(i) & 255).toInt
        bytes(511L + i * 2) = (bf(i) >> 8).toInt
      }
      for (a <- 638 until 676) bytes(a.toLong) = 0xa5
      run(Seq(addi(6, 0, 510), addi(7, 0, 640), instruction(61, 5, 6, 0),
        instruction(58, 6, 0, 5), instruction(58, 8, 1, 6),
        instruction(58, 8, 4, 5), instruction(58, 9, 5, 5),
        instruction(62, 8, 7, 2), fence, halt))
      dut.io.debugVRegs(5).expect(packed(bf)); dut.io.debugVRegs(8).expect(packed(bf))
      dut.io.debugRegs(8).expect(0x43080000L); dut.io.debugRegs(9).expect(0x4180)
      assert(bytes(640) == 0xa5 && bytes(641) == 0xa5 && bytes(674) == 0xa5)
      for (i <- bf.indices) assert((bytes(642L + i * 2) | (bytes(643L + i * 2) << 8)) == bf(i))
      // Precheck the whole active footprint: valid early lanes cannot write before a later fault.
      put32(p.dtcmBytes - 4, BigInt("deadbeef", 16))
      run(Seq(lui(10, 1), addi(10, 10, -4), instruction(60, 0, 10, 0),
        addi(11, 0, 99), halt), 7, 8, p.dtcmBytes)
      assert(requests.isEmpty); assert(get32(p.dtcmBytes - 4) == BigInt("deadbeef", 16))
      dut.io.debugRegs(11).expect(0)
      run(Seq(addi(10, 0, 3), instruction(61, 0, 10, 0), halt), 4, 4, 3)
      assert(requests.isEmpty); dut.io.debugVRegs(0).expect(old0)
      // A late read error does not partially update a vector destination or authorize younger stores.
      errorAddress = Some(8)
      run(Seq(instruction(59, 0, 0, 0), instruction(60, 0, 2, 0), halt), 5, 0, 8)
      assert(requests.map(_._1).toSeq == Seq(0L, 4L, 8L)); assert(requests.forall(!_._2))
      dut.io.debugVRegs(0).expect(old0); errorAddress = None
      // Restart retains architectural vectors; independent vector compute may finish behind a scalar load.
      run(Seq(instruction(15, 7, 0, 7), addi(3, 0, 12), halt)) // vxor v7,v7,x0: initially zero
      beforeV7 = dut.io.debugVRegs(7).peek().litValue
      requestDelay = 120; monitorEarly = true
      run(Seq(lw(12, 0, 0), instruction(1, 7, 3, 7), halt))
      assert(earlyResult); dut.io.debugVRegs(7).expect(packed(Seq.fill(16)(BigInt(12))))
      monitorEarly = false; requestDelay = 3
      // Repeated aliases exhaust generation/age counters without losing old destination versions.
      run(Seq(addi(3, 0, 1)) ++ Seq.fill(80)(instruction(1, 7, 3, 7)) ++ Seq(halt))
      dut.io.debugVRegs(7).expect(packed(Seq.fill(16)(BigInt(92))))
      run(Seq(instruction(63, 7, 0, 0), instruction(60, 7, 2, 0), halt),
        1, 0, instruction(63, 7, 0, 0).toLong)
      assert(requests.isEmpty); dut.io.debugVRegs(7).expect(packed(Seq.fill(16)(BigInt(92))))
      for (bad <- Seq(instruction(58, 7, 9, 5), instruction(56, 7, 0, 0))) {
        run(Seq(bad, instruction(60, 7, 2, 0), halt), 1, 0, bad.toLong)
        assert(requests.isEmpty)
      }
      // A runtime write error retains earlier acknowledged lanes, but blocks all younger stores.
      val secondWord = get32(132)
      errorAddress = Some(132)
      run(Seq(instruction(60, 7, 2, 0), addi(14, 0, 99), instruction(60, 7, 2, 0), halt),
        7, 0, 132)
      assert(requests.map(_._1).toSeq == Seq(128L, 132L))
      assert(get32(128) == 92 && get32(132) == secondWord)
      dut.io.debugRegs(14).expect(0)
      // Report the BF16 element address, not the aligned word carrying its response.
      errorAddress = Some(508)
      run(Seq(instruction(61, 5, 6, 0), halt), 5, 0, 510)
      dut.io.debugVRegs(5).expect(packed(bf)); errorAddress = None
      run(Seq(instruction(58, 0, 4, 5), halt))
      dut.io.debugRegs(0).expect(0)
    }
  }
  private def lui(rd: Int, upper: Int): BigInt = (BigInt(upper) << 12) | (rd << 7) | 0x37
}
