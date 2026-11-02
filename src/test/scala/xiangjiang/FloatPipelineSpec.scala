package xiangjiang

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.fu.VectorFloat

/** Reference functions remain combinational only in the test harness. */
class FloatPipelineHarness extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W))
    val rm = Input(UInt(3.W))
    val reference = Output(Vec(6, UInt(37.W)))
    val piped = Output(Vec(6, UInt(37.W)))
    val splitAdd32 = Output(UInt(32.W))
    val splitAdd16 = Output(UInt(16.W))
  })
  io.splitAdd32 := VectorFloat.add32(io.a, io.b, pipelined = true, splitInput = true)
  io.splitAdd16 := VectorFloat.add(io.a, io.b, pipelined = true, splitInput = true)
  for ((out, pipe) <- Seq(io.reference -> false, io.piped -> true)) {
    val add = VectorFloat.f32Add(io.a, io.b, io.rm, pipe)
    val mul = VectorFloat.f32Mul(io.a, io.b, io.rm, pipe)
    out(0) := Cat(add._2, add._1)
    out(1) := Cat(mul._2, mul._1)
    out(2) := VectorFloat.add32(io.a, io.b, pipe)
    out(3) := VectorFloat.mul32(io.a, io.b, pipe)
    out(4) := VectorFloat.add(io.a, io.b, pipe)
    out(5) := VectorFloat.mul(io.a, io.b, pipe)
  }
}

class FloatPipelineSpec extends AnyFunSuite with ChiselSim {
  test("every-cycle operands and rounding modes remain aligned across all four internal stages") {
    simulate(new FloatPipelineHarness) { d =>
      val random = new scala.util.Random(100)
      val edges = Seq(0L, 0x80000000L, 1L, 0x007fffffL, 0x00800000L,
        0x3f800000L, 0x7f7fffffL, 0x7f800000L, 0xff800000L, 0x7fc00001L, 0x7f800001L)
      val inputs = (for (a <- edges; b <- edges) yield (BigInt(a), BigInt(b))) ++
        Seq.fill(300)((BigInt(32, random), BigInt(32, random)))
      val queue = scala.collection.mutable.Queue.empty[Seq[BigInt]]
      val splitQueue = scala.collection.mutable.Queue.empty[(BigInt, BigInt)]
      for (((a, b), i) <- inputs.zipWithIndex) {
        d.io.a.poke(a); d.io.b.poke(b); d.io.rm.poke(i % 5)
        queue.enqueue(d.io.reference.map(_.peek().litValue).toSeq)
        splitQueue.enqueue((d.io.reference(2).peek().litValue, d.io.reference(4).peek().litValue))
        d.clock.step()
        if (splitQueue.size >= 5) {
          val (fp32, bf16) = splitQueue.dequeue()
          assert(d.io.splitAdd32.peek().litValue == fp32, s"split FP32 sample=$i")
          assert(d.io.splitAdd16.peek().litValue == bf16, s"split BF16 sample=$i")
        }
        if (queue.size >= 4) {
          val expected = queue.dequeue()
          for (lane <- 0 until 6)
            assert(d.io.piped(lane).peek().litValue == expected(lane), s"pipeline=$lane sample=$i")
        }
      }
    }
  }
}
