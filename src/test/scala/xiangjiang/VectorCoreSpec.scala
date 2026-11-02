package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

/** Test-side vector machine encoding for the 7-bit VecOp space.
  * vecOp = {inst[25], funct3, group}; groups 0/1 use OP-V and AMO, groups
  * 2..5 use custom-1 funct3 4..7 (see docs/XJ-ISA-Support.md).
  */
object VectorEncoding {
  def instruction(id: Int, rd: Int, rs1: Int, rs2: Int,
                  mask: Option[Int] = None): BigInt = {
    require(id >= 0 && id < 128)
    val basicFp = id >= VecOp.vfsub32 && id <= VecOp.vfmul32s
    val idx = id & 7
    val f3 = (id >> 3) & 7
    val bit25 = if (basicFp) id & 1 else (id >> 6) & 1
    val opcode = if (basicFp) 0x7b else if (idx == 0) 0x57 else if (idx == 1) 0x2f else 0x2b
    val f3Field = if (basicFp) { if (id <= VecOp.vfsub32s) 4 else 5 }
      else if (idx >= 2) 4 + (idx - 2) else f3
    (BigInt(mask.getOrElse(0)) << 27) | (if (mask.isEmpty) BigInt(1) << 26 else BigInt(0)) |
      (BigInt(bit25) << 25) | ((rs2 & 31) << 20) | (rs1 << 15) |
      (BigInt(f3Field) << 12) | (rd << 7) | opcode
  }
  def packed32(words: Seq[BigInt]): BigInt =
    words.zipWithIndex.foldLeft(BigInt(0)) { case (r, (v, i)) =>
      r | ((v & BigInt("ffffffff", 16)) << (i * 32))
    }
  def packed16(halves: Seq[BigInt]): BigInt =
    halves.zipWithIndex.foldLeft(BigInt(0)) { case (r, (v, i)) =>
      r | ((v & 0xffff) << (i * 16))
    }
  def packed8(bytes: Seq[BigInt]): BigInt =
    bytes.zipWithIndex.foldLeft(BigInt(0)) { case (r, (v, i)) =>
      r | ((v & 0xff) << (i * 8))
    }
}

class VectorCoreSpec extends AnyFunSuite with ChiselSim {
  test("vset configuration, 128-bit SEW execution, masking, memory and restart") {
    val p = XJParams(nRob = 8, nIssue = 2, nXRes = 3, nMemq = 4,
      generationBits = 1, seqBits = 6, bankBytes = 1024, nVRes = 4)
    simulate(new ScalarCore(p)) { dut =>
      import Rv32._
      import VectorEncoding._
      val bytes = scala.collection.mutable.Map.empty[Long, Int]
      def put32(address: Long, value: BigInt): Unit =
        for (i <- 0 until 4) bytes(address + i) = ((value >> (8 * i)) & 255).toInt
      def get32(address: Long): BigInt = (0 until 4).foldLeft(BigInt(0)) { (v, i) =>
        v | (BigInt(bytes.getOrElse(address + i, 0)) << (8 * i))
      }
      var program = Seq.empty[BigInt]
      var cycles = 0
      var response: Option[(Int, BigInt, Boolean)] = None
      val requests = scala.collection.mutable.ArrayBuffer.empty[(Long, Boolean, BigInt, Int)]
      val commits = scala.collection.mutable.ArrayBuffer.empty[Int]
      var errorAddress: Option[Long] = None
      dut.io.start.poke(false); dut.io.ack.poke(false)
      dut.io.fetchReq.ready.poke(true); dut.io.fetchResp.valid.poke(false)
      dut.io.fetchResp.bits.pc.poke(0); dut.io.fetchResp.bits.inst.poke(0)
      dut.io.fetchResp.bits.exception.poke(false); dut.io.fetchResp.bits.cause.poke(0)
      dut.io.fetchResp.bits.badAddr.poke(0)
      dut.io.dataReq.ready.poke(false); dut.io.dataResp.valid.poke(false)
      dut.io.dataResp.bits.data.poke(0); dut.io.dataResp.bits.error.poke(false)
      dut.io.matrixReq.ready.poke(false); dut.io.matrixResp.valid.poke(false)
      dut.io.matrixResp.bits.data.poke(0)
      dut.io.dmaCommand.ready.poke(false); dut.io.dmaCompletion.valid.poke(false)
      dut.io.dmaBusy.poke(false)

      def tick(): Unit = {
        dut.io.dataReq.ready.poke(cycles % 4 != 1)
        dut.io.dataResp.valid.poke(response.exists(_._1 == 0))
        response.foreach { case (_, data, error) =>
          dut.io.dataResp.bits.data.poke(data); dut.io.dataResp.bits.error.poke(error)
        }
        val fetch = if (dut.io.fetchReq.valid.peek().litToBoolean)
          Some(dut.io.fetchReq.bits.peek().litValue.toInt) else None
        if (dut.io.retire.valid.peek().litToBoolean &&
          !dut.io.retire.bits.exception.peek().litToBoolean)
          commits += dut.io.retire.bits.uop.pc.peek().litValue.toInt
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
          response = Some((3, get32(addr), error))
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
        val deadline = cycles + 30000
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

      // Memory preload: words 0..3 at 0, 10..13 at 64.
      for (i <- 0 until 4) { put32(i * 4, i); put32(64 + i * 4, 10 + i) }

      // SEW=32, vl=4: vle32 then vadd.vv then vse32.
      run(Seq(
        addi(4, 0, 4), vsetvli(5, 4, vtype(sew = 2, lmul = 0)),
        addi(1, 0, 64),
        instruction(VecOp.vle32, 1, 1, 0),
        instruction(VecOp.vadd, 2, 1, 0),
        addi(3, 0, 128),
        instruction(VecOp.vse32, 2, 3, 0),
        fence, halt))
      dut.io.debugVRegs(1).expect(packed32(Seq(10, 11, 12, 13)))
      dut.io.debugVRegs(2).expect(packed32(Seq(10, 11, 12, 13)))
      for (i <- 0 until 4) assert(get32(128L + 4 * i) == BigInt(10 + i))
      // The vsetvli rd receives the computed vl.
      dut.io.debugRegs(5).expect(4)

      // vadd with vs2 loaded earlier: 10+i + (i) at 0.
      run(Seq(
        addi(4, 0, 4), vsetvli(5, 4, vtype(sew = 2, lmul = 0)),
        addi(1, 0, 0),
        instruction(VecOp.vle32, 1, 1, 0),
        instruction(VecOp.vadd, 4, 2, 1), // vd4 = v2 + v1 = 10+i + i
        fence, halt))
      dut.io.debugVRegs(4).expect(packed32(Seq(10, 12, 14, 16)))

      // SEW=8: vl=16, byte add (v1 currently holds words 0..3).
      run(Seq(
        addi(4, 0, 16), vsetvli(5, 4, vtype(sew = 0, lmul = 0)),
        instruction(VecOp.vadd, 5, 1, 1), // bytes double: w*2 mod 256
        fence, halt))
      val bytesExpected = (0 until 16).map { i =>
        val src = (0 until 4).flatMap(w => Seq(
          w & 0xff, (w >> 8) & 0xff, 0, 0))(i)
        (src + src) & 0xff
      }
      dut.io.debugVRegs(5).expect(packed8(bytesExpected.map(BigInt(_))))

      // Tail preservation: vl=2 write keeps elements 2..3.
      run(Seq(
        addi(4, 0, 2), vsetvli(5, 4, vtype(sew = 2, lmul = 0)),
        addi(6, 0, 100),
        instruction(VecOp.vadd + 1, 2, 6, 2), // v2[0:2] += 100, tail preserved
        fence, halt))
      dut.io.debugVRegs(2).expect(packed32(Seq(110, 111, 12, 13)))

      // Masked operation: v0 mask with bits 0 and 2 → only those update.
      put32(256L, 5); put32(260L, 5); put32(264L, 5); put32(268L, 5)
      run(Seq(
        addi(4, 0, 4), vsetvli(5, 4, vtype(sew = 2, lmul = 0)),
        addi(7, 0, 256),
        instruction(VecOp.vle32, 0, 7, 0), // unmasked: v0 = 5,5,5,5
        addi(8, 0, 1000),
        instruction(VecOp.vadd + 1, 2, 8, 2, mask = Some(0)), // vd2 = v2 + 1000, masked
        fence, halt))
      dut.io.debugVRegs(0).expect(packed32(Seq(5, 5, 5, 5)))
      dut.io.debugVRegs(2).expect(packed32(Seq(1110, 111, 1012, 13)))

      // Executable replacements for vector fused multiply-add, including
      // dependent producers and masked tail preservation.
      for (i <- 0 until 4) put32(512L + i * 4, 0x40000000L) // 2.0
      run(Seq(
        vsetivli(5, 4, vtype(sew = 2, lmul = 0)), addi(1, 0, 512),
        instruction(VecOp.vle32, 1, 1, 0),
        instruction(VecOp.vfmul32, 3, 1, 1), // 4.0
        instruction(VecOp.vfadd32, 3, 1, 3), // 6.0, two-rounding lowering
        instruction(VecOp.vfsub32, 4, 1, 3), // 4.0
        flw(1, 1, 0),
        instruction(VecOp.vfmul32s, 4, 1, 4), // 8.0, F scalar source
        vsetivli(5, 2, vtype(sew = 2, lmul = 0)),
        instruction(VecOp.vfsub32s, 4, 1, 4, mask = Some(0)),
        fence, halt))
      assert(dut.io.debugVRegs(3).peek().litValue == packed32(Seq.fill(4)(BigInt(0x40c00000L))))
      assert(dut.io.debugVRegs(4).peek().litValue ==
        packed32(Seq(0x40c00000L, 0x41000000L, 0x41000000L, 0x41000000L).map(BigInt(_))))
      // Removed instructions fault precisely; younger stores never execute.
      for (removed <- Seq(fdivs(2, 1, 1), fsqrts(2, 1), fmadds(2, 1, 1, 1),
        instruction(VecOp.vfmacc32, 2, 1, 1))) {
        run(Seq(fmuls(3, 1, 1), removed, sw(1, 1, 0), halt),
          FaultCode.illegal.litValue.toInt, 4, removed.toLong)
        assert(requests.isEmpty)
        assert(dut.io.status.fault.peek().litToBoolean)
        assert(dut.io.status.faultPc.peek().litValue == 4)
      }
      run(Seq(addi(5, 0, 123), halt)) // restart after drained FP pipeline
      assert(dut.io.debugRegs(5).peek().litValue == 123)

      // vill: LMUL=2 is outside the baseline; vl becomes 0 and vtype gets vill.
      run(Seq(addi(4, 0, 8), vsetvli(5, 4, vtype(sew = 2, lmul = 1)), fence, halt))
      dut.io.debugRegs(5).expect(0)
      // Executing a vector instruction under vill raises vtypeVill.
      run(Seq(
        instruction(VecOp.vadd, 2, 1, 0), fence, halt),
        FaultCode.vtypeVill.litValue.toInt, 0,
        instruction(VecOp.vadd, 2, 1, 0).toLong)
      // SEW=64 is unsupported too.
      run(Seq(addi(4, 0, 4), vsetvli(5, 4, vtype(sew = 3, lmul = 0)), fence, halt))
      dut.io.debugRegs(5).expect(0)

      // vsetvl reads vtype from rs2; vsetivli takes an immediate AVL.
      run(Seq(
        addi(9, 0, vtype(sew = 1, lmul = 0)), // SEW=16
        addi(10, 0, 3),
        vsetvl(5, 10, 9),
        fence, halt))
      dut.io.debugRegs(5).expect(3) // min(3, 8)
      run(Seq(
        vsetivli(5, 2, vtype(sew = 2, lmul = 0)),
        fence, halt))
      dut.io.debugRegs(5).expect(2)

      // Non-zero vstart blocks vector execution (baseline: no resumption).
      run(Seq(
        csrrwi(0, 0x008, 3), // vstart := 3
        instruction(VecOp.vadd, 2, 1, 0),
        fence, halt), FaultCode.vstartActive.litValue.toInt, 4,
        instruction(VecOp.vadd, 2, 1, 0).toLong)
      // vset* resets vstart.
      run(Seq(addi(4, 0, 4), vsetvli(5, 4, vtype(sew = 2, lmul = 0)), fence, halt))
      dut.io.debugRegs(5).expect(4)

      // Zero active elements: no requests, ordered completion, old value kept.
      val v1Before = dut.io.debugVRegs(1).peek().litValue
      run(Seq(
        addi(4, 0, 0), vsetvli(5, 4, vtype(sew = 2, lmul = 0)),
        addi(11, 0, 200),
        instruction(VecOp.vle32, 1, 11, 0),
        fence, halt))
      assert(requests.isEmpty)
      dut.io.debugVRegs(1).expect(v1Before)

      // Masked load: v0 = 1 → only element 0 is active and in range.
      val v3Before = dut.io.debugVRegs(3).peek().litValue
      put32(272L, 1)
      put32(p.dtcmBytes - 4, BigInt("87654321", 16))
      run(Seq(
        addi(4, 0, 4), vsetvli(5, 4, vtype(sew = 2, lmul = 0)),
        addi(7, 0, 272),
        instruction(VecOp.vle32, 0, 7, 0),
        lui(12, 1),
        addi(12, 12, -4),
        instruction(VecOp.vle32, 3, 12, 0, mask = Some(0)),
        fence, halt))
      assert(requests.map(_._1).toSeq == Seq(272L, 276L, 280L, 284L, (p.dtcmBytes - 4).toLong))
      dut.io.debugVRegs(3).expect((v3Before & ~BigInt("ffffffff", 16)) | BigInt("87654321", 16))

      // Illegal encodings raise illegal instructions.
      for (bad <- Seq(instruction(63, 7, 0, 0), instruction(70, 7, 0, 0),
        instruction(71, 7, 0, 0), instruction(83, 7, 0, 0),
        instruction(100, 7, 0, 0), instruction(127, 7, 0, 0))) {
        run(Seq(bad, fence, halt), FaultCode.illegal.litValue.toInt, 0, bad.toLong)
      }

      // A masked load error blocks the whole instruction precisely.
      errorAddress = Some(8)
      run(Seq(
        addi(4, 0, 4), vsetvli(5, 4, vtype(sew = 2, lmul = 0)),
        addi(13, 0, 0),
        instruction(VecOp.vle32, 0, 13, 0),
        addi(14, 0, 99),
        fence, halt), FaultCode.loadAccess.litValue.toInt, 12, 8)
      assert(requests.forall(!_._2))
      dut.io.debugRegs(14).expect(0)
      errorAddress = None

      // Restart after fault retains committed vector state.
      run(Seq(vsetvli(0, 4, vtype(sew = 2, lmul = 0)), fence, halt))
      dut.io.debugVRegs(2).expect(packed32(Seq(1110, 111, 1012, 13)))
    }
  }
}
