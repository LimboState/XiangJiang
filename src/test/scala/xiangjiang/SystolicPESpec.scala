package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.fu.SystolicPE

class SystolicPESpec extends AnyFunSuite with ChiselSim {
  test("BF16 PE rounds products before two-stage FP32 accumulation and drains exactly once") {
    simulate(new SystolicPE(16, bf16 = true)) { dut =>
      dut.io.aIn.poke(0)
      dut.io.bIn.poke(0)
      dut.io.aInValid.poke(false)
      dut.io.bInValid.poke(false)
      dut.io.advance.poke(false)
      dut.io.clearPipe.poke(false)
      dut.io.clearAcc.poke(true)
      dut.io.loadAccValid.poke(false)
      dut.io.loadAccData.poke(0)
      dut.clock.step()
      dut.io.clearAcc.poke(false)

      def expectAcc(bits: Long): Unit = dut.io.acc.expect(BigInt(bits))
      def pair(a: Int, b: Int): Unit = {
        dut.io.advance.poke(true)
        dut.io.aInValid.poke(true)
        dut.io.bInValid.poke(true)
        dut.io.aIn.poke(a)
        dut.io.bIn.poke(b)
        dut.clock.step()
      }
      def drain(): Unit = {
        dut.io.advance.poke(false)
        dut.io.aInValid.poke(false)
        dut.io.bInValid.poke(false)
        dut.clock.step()
      }
      def load(bits: Long): Unit = {
        dut.io.loadAccValid.poke(true)
        dut.io.loadAccData.poke(BigInt(bits))
        dut.clock.step()
        dut.io.loadAccValid.poke(false)
      }

      // (1 + 1/128)^2 = 1 + 1/64 + 1/16384. BF16 product
      // rounds to 0x3f82, not the old FP32 product 0x3f820200.
      pair(0x3f81, 0x3f81)
      expectAcc(0)
      dut.io.aOut.expect(0x3f81)
      dut.io.aOutValid.expect(true)
      pair(0x3f80, 0x3f80)
      expectAcc(0x3f820000L)
      drain()
      expectAcc(0x40010000L)
      dut.clock.step(3)
      expectAcc(0x40010000L) // no repeated accumulation on idle cycles

      // Accumulator retains FP32 precision, rather than rounding to BF16.
      load(0x3f800000L)
      pair(0x3580, 0x3f80) // 2^-20 * 1
      expectAcc(0x3f800000L)
      drain()
      expectAcc(0x3f800008L)

      // Cancellation distinguishes product rounding even after a BF16 store:
      // rounded product + (-rounded product) is exactly zero.
      load(0xbf820000L)
      pair(0x3f81, 0x3f81)
      drain()
      expectAcc(0)

      // An invalid operand inserts a bubble, never a stale product.
      pair(0x3f80, 0x3f80)
      dut.io.bInValid.poke(false)
      dut.clock.step()
      expectAcc(0x3f800000L)
      drain()
      expectAcc(0x3f800000L)

      // Flush cancels an accepted-but-not-accumulated product and A/B valids.
      pair(0x4000, 0x4000)
      dut.io.clearPipe.poke(true)
      dut.clock.step()
      dut.io.aOutValid.expect(false)
      dut.io.bOutValid.expect(false)
      dut.io.clearPipe.poke(false)
      drain()
      expectAcc(0x3f800000L)
      pair(0x4000, 0x3f80)
      drain()
      expectAcc(0x40400000L)

      // ACC load/clear override pending MACs and invalidate the product stage.
      pair(0x4000, 0x4000)
      load(0x40a00000L)
      drain()
      expectAcc(0x40a00000L)
      pair(0x4000, 0x4000)
      dut.io.clearAcc.poke(true)
      dut.clock.step()
      dut.io.clearAcc.poke(false)
      drain()
      expectAcc(0)

      // Product underflow/overflow now occurs at BF16 precision/range.
      pair(0x0001, 0x3f00) // smallest BF16 subnormal * 0.5: tie to zero
      drain()
      expectAcc(0)
      pair(0x7f7f, 0x4000) // max finite BF16 * 2 -> infinity
      drain()
      expectAcc(0x7f800000L)
      pair(0x7f80, 0x0000) // infinity * zero -> canonical NaN
      drain()
      expectAcc(0x7fc00000L)
    }
  }
}
