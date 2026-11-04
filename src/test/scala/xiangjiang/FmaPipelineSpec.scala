package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.fu.{SystolicPE, VectorFloat}

class FmaPipelineHarness extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W))
    val c = Input(UInt(32.W))
    val reference = Output(UInt(32.W))
    val pipelined = Output(UInt(32.W))
  })
  io.reference := VectorFloat.fma32(io.a, io.b, io.c)
  io.pipelined := VectorFloat.fma32Pipelined(io.a, io.b, io.c)
}

class BFloatPipelineHarness extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(16.W))
    val b = Input(UInt(16.W))
    val reference = Output(Vec(3, UInt(16.W)))
    val pipelined = Output(Vec(3, UInt(16.W)))
  })
  io.reference(0) := VectorFloat.add(io.a, io.b)
  io.reference(1) := VectorFloat.sub(io.a, io.b)
  io.reference(2) := VectorFloat.mul(io.a, io.b)
  io.pipelined(0) := VectorFloat.addPipelined(io.a, io.b)
  io.pipelined(1) := VectorFloat.subPipelined(io.a, io.b)
  io.pipelined(2) := VectorFloat.mulPipelined(io.a, io.b)
}

class FmaPipelineSpec extends AnyFunSuite with ChiselSim {
  test("BF16 add sub mul pipelines preserve results and align specials at full throughput") {
    simulate(new BFloatPipelineHarness) { d =>
      val random = new scala.util.Random(727)
      val specials = Seq(0, 0x8000, 1, 0x7f, 0x80, 0x3f80, 0xbf80,
        0x7f7f, 0x7f80, 0xff80, 0x7fc0, 0x7f81)
      val inputs = (for (a <- specials; b <- specials) yield (a, b)) ++
        Seq.fill(1000)((random.nextInt(65536), random.nextInt(65536)))
      val expected = scala.collection.mutable.Queue.empty[Seq[BigInt]]
      for ((a, b) <- inputs ++ Seq.fill(4)((0, 0))) {
        d.io.a.poke(a); d.io.b.poke(b)
        expected.enqueue(d.io.reference.map(_.peek().litValue).toSeq)
        d.clock.step()
        if (expected.size >= 4) {
          for ((port, value) <- d.io.pipelined.zip(expected.dequeue())) port.expect(value.U)
        }
      }
    }
  }

  test("six-stage FMA matches the combinational fused result at full pipeline throughput") {
    simulate(new FmaPipelineHarness) { d =>
      val random = new scala.util.Random(726)
      val specials = Seq(0L, 0x80000000L, 1L, 0x007fffffL, 0x00800000L,
        0x3f800000L, 0xbf800000L, 0x7f7fffffL, 0x7f800000L, 0xff800000L, 0x7fc00000L)
      val vectors = (for (a <- specials; b <- specials) yield (a, b, specials(random.nextInt(specials.size)))) ++
        Seq.fill(500)((random.nextLong() & 0xffffffffL, random.nextLong() & 0xffffffffL,
          random.nextLong() & 0xffffffffL))
      val expected = scala.collection.mutable.Queue.empty[BigInt]
      for ((a, b, c) <- vectors ++ Seq.fill(VectorFloat.fma32PipelineStages)((0L, 0L, 0L))) {
        d.io.a.poke(a.U); d.io.b.poke(b.U); d.io.c.poke(c.U)
        expected.enqueue(d.io.reference.peek().litValue)
        d.clock.step()
        if (expected.size >= VectorFloat.fma32PipelineStages) {
          d.io.pipelined.expect(expected.dequeue().U)
        }
      }
    }
  }

  test("BF16 PE drains once and flush cancels an in-flight accumulator update") {
    simulate(new SystolicPE(16, true)) { d =>
      d.io.aIn.poke(0x3f80); d.io.bIn.poke(0x4000)
      d.io.aInValid.poke(true); d.io.bInValid.poke(true)
      d.io.tagIn.poke(0); d.io.moveA.poke(false); d.io.moveB.poke(false)
      d.io.clearPipe.poke(false); d.io.sumIn.poke(0); d.io.sumInValid.poke(false)
      d.io.macEnable.poke(false); d.io.useAccumulator.poke(true); d.io.useStoredB.poke(false)
      d.io.loadAccumulator.poke(true); d.io.accumulatorIn.poke(0x3f800000L)
      d.clock.step(); d.io.loadAccumulator.poke(false)
      d.io.macEnable.poke(true); d.clock.step(); d.io.macEnable.poke(false)
      d.clock.step(VectorFloat.fma32PipelineStages)
      d.io.accumulator.expect(0x40400000L.U) // 1 + 1*2 = 3
      d.clock.step(10); d.io.accumulator.expect(0x40400000L.U)
      d.io.macEnable.poke(true); d.clock.step(); d.io.macEnable.poke(false)
      d.clock.step(2); d.io.clearPipe.poke(true); d.clock.step(); d.io.clearPipe.poke(false)
      d.clock.step(10); d.io.accumulator.expect(0x40400000L.U)
      d.io.macEnable.poke(true); d.clock.step(); d.io.macEnable.poke(false)
      d.clock.step(VectorFloat.fma32PipelineStages)
      d.io.accumulator.expect(0x40a00000L.U) // restart: 3 + 2 = 5
    }
  }
}
