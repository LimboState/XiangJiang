package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.fu.SystolicPE

class SystolicPESpec extends AnyFunSuite with ChiselSim {
  test("pipelined BF16 PE preserves sequential rounding at five-clock cadence and cancels in-flight work") {
    simulate(new SystolicPE(16, bf16 = true)) { d =>
      d.io.aIn.poke(0); d.io.bIn.poke(0)
      d.io.aInValid.poke(false); d.io.bInValid.poke(false)
      d.io.advance.poke(false); d.io.clearPipe.poke(false)
      d.io.clearAcc.poke(true); d.io.loadAccValid.poke(false); d.io.loadAccData.poke(0)
      d.clock.step(); d.io.clearAcc.poke(false)
      def bubble(n: Int): Unit = {
        d.io.advance.poke(false); d.io.aInValid.poke(false); d.io.bInValid.poke(false)
        d.clock.step(n)
      }
      def pair(a: Int, b: Int): Unit = {
        d.io.advance.poke(true); d.io.aInValid.poke(true); d.io.bInValid.poke(true)
        d.io.aIn.poke(a); d.io.bIn.poke(b); d.clock.step()
      }
      def load(x: Long): Unit = {
        d.io.advance.poke(false); d.io.loadAccValid.poke(true); d.io.loadAccData.poke(BigInt(x))
        d.clock.step(); d.io.loadAccValid.poke(false)
      }
      def mac(a: Int, b: Int, expected: Long): Unit = {
        pair(a, b); bubble(9); d.io.acc.expect(BigInt(expected))
        bubble(3); d.io.acc.expect(BigInt(expected))
      }
      pair(0x3f81, 0x3f81)
      d.io.aOut.expect(0x3f81); d.io.aOutValid.expect(true)
      d.io.acc.expect(0)
      bubble(4)
      pair(0x3f80, 0x3f80) // exactly five clocks after the first
      bubble(4); d.io.acc.expect(0x3f820000L)
      bubble(5); d.io.acc.expect(0x40010000L)
      bubble(10); d.io.acc.expect(0x40010000L)
      load(0x3f800000L); mac(0x3580, 0x3f80, 0x3f800008L)
      load(0xbf820000L); mac(0x3f81, 0x3f81, 0)
      for (age <- Seq(0, 3, 5, 8)) {
        load(0x3f800000L)
        pair(0x4000, 0x4000); bubble(age)
        d.io.clearPipe.poke(true); d.clock.step(); d.io.clearPipe.poke(false)
        d.io.aOutValid.expect(false); d.io.bOutValid.expect(false)
        bubble(12); d.io.acc.expect(0x3f800000L)
        mac(0x4000, 0x3f80, 0x40400000L)
      }
      pair(0x4000, 0x4000); load(0x40a00000L); bubble(12); d.io.acc.expect(0x40a00000L)
      pair(0x4000, 0x4000)
      d.io.clearAcc.poke(true); d.clock.step(); d.io.clearAcc.poke(false)
      bubble(12); d.io.acc.expect(0)
      mac(0x0001, 0x3f00, 0)
      mac(0x7f7f, 0x4000, 0x7f800000L)
      mac(0x7f80, 0x0000, 0x7fc00000L)
    }
  }
}
