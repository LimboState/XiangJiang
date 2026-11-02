package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.fu.VectorFloat

class Bf16NarrowHarness extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(32.W))
    val out = Output(UInt(16.W))
  })
  io.out := VectorFloat.narrow(io.in)
}

class Bf16NarrowSpec extends AnyFunSuite with ChiselSim {
  test("direct BF16 narrowing covers every retained bit pattern at RNE boundaries") {
    simulate(new Bf16NarrowHarness) { d =>
      // Independent software oracle: compare distances to adjacent BF16
      // magnitudes; on a tie choose the even retained significand.
      def expected(bits: Long): Int = {
        val sign = ((bits >>> 16) & 0x8000).toInt
        val magnitude = bits & 0x7fffffffL
        if (magnitude > 0x7f800000L) 0x7fc0
        else {
          val lower = (magnitude >>> 16).toInt
          val down = magnitude - (lower.toLong << 16)
          val up = ((lower.toLong + 1) << 16) - magnitude
          sign | (if (down < up || (down == up && (lower & 1) == 0)) lower else lower + 1)
        }
      }
      def check(bits: Long): Unit = {
        d.io.in.poke(BigInt(bits))
        assert(d.io.out.peek().litValue == expected(bits), f"FP32 bits=$bits%08x")
      }
      for (upper <- 0 until 65536; lower <- Seq(0, 0x7fff, 0x8000, 0x8001, 0xffff))
        check((upper.toLong << 16) | lower)
      val random = new scala.util.Random(100)
      for (_ <- 0 until 2000) check(random.nextInt().toLong & 0xffffffffL)
    }
  }
}
