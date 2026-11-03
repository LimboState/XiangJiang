package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.fu.{BFloatDivider, VectorFloat}

class DividerHarness extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(16.W)); val b = Input(UInt(16.W))
    val start = Input(Bool()); val ready = Output(Bool())
    val valid = Output(Bool()); val accept = Input(Bool()); val flush = Input(Bool())
    val data = Output(UInt(16.W)); val reference = Output(UInt(16.W))
  })
  val d = Module(new BFloatDivider)
  d.io.req.valid := io.start; d.io.req.bits.a := io.a; d.io.req.bits.b := io.b
  io.ready := d.io.req.ready; d.io.resp.ready := io.accept; d.io.flush := io.flush
  io.valid := d.io.resp.valid; io.data := d.io.resp.bits
  io.reference := VectorFloat.div(io.a, io.b)
}

class DividerSpec extends AnyFunSuite with ChiselSim {
  test("iterative BF16 division matches the rational-oracle-validated combinational path") {
    simulate(new DividerHarness) { c =>
      c.io.start.poke(false); c.io.accept.poke(false); c.io.flush.poke(false)
      c.io.a.poke(0); c.io.b.poke(0); c.clock.step()
      val random = new scala.util.Random(0xbfd1)
      val edges = Seq(0, 0x8000, 1, 0x7f, 0x80, 0x3f80, 0xbf80, 0x7f7f, 0xff7f, 0x7f80, 0xff80, 0x7fc1, 0x7f81)
      val cases = (for (a <- edges; b <- edges) yield (a, b)) ++ Seq.fill(500)((random.nextInt(65536), random.nextInt(65536)))
      for ((a, b) <- cases) {
        c.io.a.poke(a); c.io.b.poke(b)
        val expected = c.io.reference.peek().litValue
        c.io.ready.expect(true); c.io.start.poke(true); c.clock.step(); c.io.start.poke(false)
        c.io.a.poke(0); c.io.b.poke(0)
        for (_ <- 0 until 20) { c.io.valid.expect(false); c.clock.step() }
        c.io.valid.expect(true); c.io.data.expect(expected)
        c.clock.step(2); c.io.data.expect(expected)
        c.io.accept.poke(true); c.clock.step(); c.io.accept.poke(false)
      }
      c.io.a.poke(0x3f80); c.io.b.poke(0x4000); c.io.start.poke(true); c.clock.step()
      c.io.start.poke(false); c.clock.step(5); c.io.flush.poke(true); c.clock.step()
      c.io.flush.poke(false); c.io.ready.expect(true); c.clock.step(25); c.io.valid.expect(false)
    }
  }
}
