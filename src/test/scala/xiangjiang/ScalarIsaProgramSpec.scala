package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

/** Program-level scalar ISA tests: M, Zbb, the F selection, CSRs and fflags. */
class ScalarIsaProgramSpec extends AnyFunSuite with ChiselSim {
  test("M, Zbb, F, CSR and fflags programs execute and commit in order") {
    val p = XJParams(nRob = 8, nIssue = 4, nXRes = 6, nMemq = 4, nFRes = 4)
    simulate(new ScalarCore(p)) { dut =>
      import Rv32._
      var program = Seq.empty[BigInt]
      val bytes = scala.collection.mutable.Map.empty[Long, Int]
      def put32(address: Long, value: BigInt): Unit =
        for (i <- 0 until 4) bytes(address + i) = ((value >> (8 * i)) & 255).toInt
      def get32(address: Long): BigInt = (0 until 4).foldLeft(BigInt(0)) { (v, i) =>
        v | (BigInt(bytes.getOrElse(address + i, 0)) << (8 * i))
      }
      var cycles = 0
      var response: Option[(Int, BigInt, Boolean)] = None
      val commits = scala.collection.mutable.ArrayBuffer.empty[Long]
      dut.io.start.poke(false); dut.io.ack.poke(false)
      dut.io.fetchReq.ready.poke(true); dut.io.fetchResp.valid.poke(false)
      dut.io.fetchResp.bits.pc.poke(0); dut.io.fetchResp.bits.inst.poke(0)
      dut.io.fetchResp.bits.exception.poke(false); dut.io.fetchResp.bits.cause.poke(0)
      dut.io.fetchResp.bits.badAddr.poke(0)
      dut.io.dataReq.ready.poke(true); dut.io.dataResp.valid.poke(false)
      dut.io.dataResp.bits.data.poke(0); dut.io.dataResp.bits.error.poke(false)
      dut.io.matrixReq.ready.poke(false); dut.io.matrixResp.valid.poke(false)
      dut.io.dmaCommand.ready.poke(false); dut.io.dmaCompletion.valid.poke(false)
      dut.io.dmaBusy.poke(false)

      def tick(): Unit = {
        dut.io.dataResp.valid.poke(response.exists(_._1 == 0))
        response.foreach { case (_, data, error) =>
          dut.io.dataResp.bits.data.poke(data); dut.io.dataResp.bits.error.poke(error)
        }
        val fetch = if (dut.io.fetchReq.valid.peek().litToBoolean)
          Some(dut.io.fetchReq.bits.peek().litValue.toInt) else None
        if (dut.io.retire.valid.peek().litToBoolean &&
          !dut.io.retire.bits.exception.peek().litToBoolean)
          commits += dut.io.retire.bits.uop.pc.peek().litValue.toLong
        if (response.exists(_._1 == 0) && dut.io.dataResp.ready.peek().litToBoolean) response = None
        if (dut.io.dataReq.valid.peek().litToBoolean && dut.io.dataReq.ready.peek().litToBoolean) {
          assert(response.isEmpty, "multiple active SRAM transactions")
          val addr = dut.io.dataReq.bits.addr.peek().litValue.toLong
          val store = dut.io.dataReq.bits.write.peek().litToBoolean
          val data = dut.io.dataReq.bits.data.peek().litValue
          val mask = dut.io.dataReq.bits.mask.peek().litValue.toInt
          if (store)
            for (i <- 0 until 4 if ((mask >> i) & 1) != 0)
              bytes(addr + i) = ((data >> (i * 8)) & 255).toInt
          response = Some((3, get32(addr), false))
        }
        dut.clock.step(); cycles += 1
        dut.io.fetchResp.valid.poke(fetch.isDefined)
        fetch.foreach { pc =>
          assert(pc >= 0 && pc % 4 == 0 && pc / 4 < program.size, s"unexpected fetch $pc")
          dut.io.fetchResp.bits.pc.poke(pc); dut.io.fetchResp.bits.inst.poke(program(pc / 4))
        }
        response = response.map { case (delay, data, error) => (math.max(0, delay - 1), data, error) }
      }
      def run(words: Seq[BigInt]): Unit = {
        if (dut.io.status.done.peek().litToBoolean || dut.io.status.fault.peek().litToBoolean) {
          dut.io.ack.poke(true); tick(); dut.io.ack.poke(false)
        }
        program = words; commits.clear()
        dut.io.start.poke(true); tick(); dut.io.start.poke(false)
        val deadline = cycles + 40000
        while (dut.io.status.busy.peek().litToBoolean && cycles < deadline) tick()
        dut.io.status.busy.expect(false)
        dut.io.status.fault.expect(false)
        dut.io.status.done.expect(true)
        assert(commits.toSeq == words.indices.map(_ * 4), s"retirement PCs $commits")
      }
      val bits32 = (BigInt(1) << 32) - 1
      def w(x: BigInt): BigInt = x & bits32

      // M extension: products, division boundaries and remainders.
      run(Seq(
        addi(1, 0, -7), addi(2, 0, 3),
        mul(3, 1, 2), mulh(4, 1, 2), mulhu(5, 1, 2), mulhsu(6, 1, 2),
        div(7, 1, 2), rem(8, 1, 2),
        fence, halt))
      dut.io.debugRegs(3).expect(w(-21))
      dut.io.debugRegs(4).expect(w(BigInt(-1))) // high of -21
      dut.io.debugRegs(5).expect(2) // unsigned high of 0xFFFFFFF9 x 3
      dut.io.debugRegs(6).expect(w(BigInt(-1))) // signed x unsigned high
      dut.io.debugRegs(7).expect(w(-2)) // -7 / 3 truncates toward zero
      dut.io.debugRegs(8).expect(w(-1)) // -7 rem 3 = -1
      // Division by zero and signed overflow boundaries.
      run(Seq(
        addi(1, 0, 5), addi(2, 0, 0),
        div(3, 1, 2), divu(4, 1, 2), rem(5, 1, 2), remu(6, 1, 2),
        lui(7, 0x80000), addi(8, 0, -1),
        div(9, 7, 8), rem(10, 7, 8),
        fence, halt))
      dut.io.debugRegs(3).expect(w(BigInt(-1)))
      dut.io.debugRegs(4).expect(bits32)
      dut.io.debugRegs(5).expect(5)
      dut.io.debugRegs(6).expect(5)
      dut.io.debugRegs(9).expect(w(BigInt(1) << 31)) // overflow returns the dividend
      dut.io.debugRegs(10).expect(0)

      // Zbb selection.
      run(Seq(
        lui(1, 0x12345), addi(1, 1, 0x678),
        clz(2, 1), ctz(3, 1), cpop(4, 1),
        sextb(5, 1), sexth(6, 1), zexth(7, 1),
        rev8(8, 1), orcb(9, 1),
        addi(10, 0, 5), andn(11, 1, 10), orn(12, 1, 10), xnor(13, 1, 10),
        addi(14, 0, -3), min(15, 1, 14), max(16, 1, 14),
        rol(17, 1, 10), ror(18, 1, 10), rori(19, 1, 4),
        fence, halt))
      val x = BigInt("12345678", 16)
      dut.io.debugRegs(2).expect(3) // clz: bit 28 is the top set bit
      dut.io.debugRegs(3).expect(3) // ctz: low 3 zero bits
      dut.io.debugRegs(4).expect(13) // popcount of 0x12345678
      dut.io.debugRegs(5).expect(w(0x78)) // sext.b: 0x78 is positive
      dut.io.debugRegs(6).expect(w(0x5678))
      dut.io.debugRegs(7).expect(0x5678)
      dut.io.debugRegs(8).expect(w(BigInt("78563412", 16)))
      dut.io.debugRegs(9).expect(bits32) // every byte is nonzero
      dut.io.debugRegs(11).expect(w(x & ~5))
      dut.io.debugRegs(12).expect(w(x | ~5))
      dut.io.debugRegs(13).expect(w(~(x ^ 5)))
      dut.io.debugRegs(15).expect(w(-3))
      dut.io.debugRegs(16).expect(x)
      dut.io.debugRegs(17).expect(w((x << 5) | (x >> 27))) // rol by 5
      dut.io.debugRegs(18).expect(w((x >> 5) | (x << 27))) // ror by 5
      dut.io.debugRegs(19).expect(w((x >> 4) | (x << 28))) // rori by 4

      // F selection: FLW/FADD.S/FMUL.S/FDIV.S/FSW and fflags via CSR.
      put32(256L, BigInt("3f800000", 16)); put32(260L, BigInt("40000000", 16))
      put32(264L, BigInt("40400000", 16)) // 3.0
      run(Seq(
        addi(1, 0, 256),
        flw(0, 1, 0), flw(1, 1, 4), flw(2, 1, 8),
        fadds(3, 0, 1), fmuls(4, 0, 1),
        csrrw(5, 0x001, 0), // clear fflags, read old
        fdivs(6, 0, 2), // 1.0 / 3.0: inexact
        addi(1, 0, 320), fsw(6, 1, 0),
        csrrs(7, 0x001, 0),
        fence, halt))
      dut.io.debugFRegs(3).expect(BigInt("40400000", 16)) // 1.0 + 2.0
      dut.io.debugFRegs(4).expect(BigInt("40000000", 16)) // 1.0 x 2.0
      dut.io.debugFRegs(6).expect(BigInt("3eaaaaab", 16)) // 1.0 / 3.0
      assert(get32(320L) == BigInt("3eaaaaab", 16))
      dut.io.debugRegs(7).expect(1) // NX sticky after 1/3
      // frm plumbing: RTZ rounds 7.5 down, RNE rounds it up.
      run(Seq(
        csrrwi(0, 0x002, 1), // frm := RTZ
        lui(1, 0x40f00), // 7.5f
        fmvwx(2, 1),
        fcvtws(3, 2, 7), // DYN: uses the frm snapshot
        csrrwi(0, 0x002, 0), // frm := RNE
        fcvtws(4, 2, 7),
        fence, halt))
      dut.io.debugRegs(3).expect(7)
      dut.io.debugRegs(4).expect(8)

      // FMADD and FMV round-trips.
      run(Seq(
        addi(1, 0, 256),
        flw(0, 1, 0), flw(1, 1, 4),
        fmadds(4, 0, 1, 0), // 1*2+1 = 3
        fmvxw(5, 4),
        fmvwx(6, 5),
        fsubs(7, 4, 6),
        feqs(8, 7, 7), flts(9, 7, 4), fles(10, 4, 4),
        fence, halt))
      dut.io.debugFRegs(4).expect(BigInt("40400000", 16)) // 3.0
      dut.io.debugRegs(5).expect(BigInt("40400000", 16))
      dut.io.debugFRegs(6).expect(BigInt("40400000", 16))
      dut.io.debugFRegs(7).expect(0) // 3 - 3 = +0
      dut.io.debugRegs(8).expect(1)
      dut.io.debugRegs(9).expect(1) // 0 < 3
      dut.io.debugRegs(10).expect(1)

      // FENCE.I commits like a barrier and invalidates fetch state.
      run(Seq(addi(1, 0, 77), fencei, addi(1, 1, 1), halt))
      dut.io.debugRegs(1).expect(78)
    }
  }
}
