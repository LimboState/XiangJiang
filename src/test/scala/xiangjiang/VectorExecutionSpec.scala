package xiangjiang

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.fu.VectorUnit

class VectorExecutionHarness(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val op = Input(UInt(6.W))
    val selector = Input(UInt(5.W))
    val unmasked = Input(Bool())
    val v1 = Input(UInt(256.W))
    val v2 = Input(UInt(256.W))
    val old = Input(UInt(256.W))
    val mask = Input(UInt(8.W))
    val scalar = Input(UInt(32.W))
    val valid = Input(Bool())
    val ready = Output(Bool())
    val flush = Input(Bool())
    val busy = Output(Bool())
    val resp = Decoupled(new ExeResult(p))
  })
  val unit = Module(new VectorUnit(p))
  unit.io.req.bits := 0.U.asTypeOf(new IssueEntry(p))
  unit.io.req.bits.uop.vecOp := io.op
  unit.io.req.bits.uop.rs1 := io.selector
  unit.io.req.bits.uop.vecUnary := io.selector(1, 0)
  unit.io.req.bits.uop.unmasked := io.unmasked
  unit.io.req.bits.uop.tag.slot := 1.U
  unit.io.req.bits.uop.tag.generation := 1.U
  unit.io.req.bits.uop.seq := 7.U
  unit.io.req.bits.uop.pc := 0x100.U
  unit.io.req.bits.vsrc1.value := io.v1
  unit.io.req.bits.vsrc2.value := io.v2
  unit.io.req.bits.vold.value := io.old
  unit.io.req.bits.vmask.value := io.mask
  unit.io.req.bits.src1.value := io.scalar
  unit.io.req.valid := io.valid
  unit.io.flush := io.flush
  io.ready := unit.io.req.ready
  io.busy := unit.io.busy
  io.resp <> unit.io.resp
}

class VectorExecutionSpec extends AnyFunSuite with ChiselSim {
  private val wordMask = (BigInt(1) << 32) - 1
  private def pack(xs: Seq[BigInt]): BigInt = VectorEncoding.packed(xs)
  private def signed(x: BigInt): BigInt = if (x.testBit(31)) x - (BigInt(1) << 32) else x

  test("eight captured lanes, canonical masks, FP32 ordered reductions and stable completion") {
    simulate(new VectorExecutionHarness(XJParams())) { c =>
      c.io.valid.poke(false); c.io.flush.poke(false); c.io.resp.ready.poke(false)
      c.io.op.poke(0); c.io.selector.poke(0); c.io.unmasked.poke(true)
      c.io.v1.poke(0); c.io.v2.poke(0); c.io.old.poke(0); c.io.mask.poke(0); c.io.scalar.poke(0)
      c.clock.step()
      val old = (0 until 8).map(i => BigInt("cafebabe", 16) + i)
      val aa = (0 until 8).map(i => (BigInt(i * 19) - 90) & wordMask)
      val bb = (0 until 8).map(i => BigInt(i + 1))
      def execute(op: Int, a: Seq[BigInt], b: Seq[BigInt], expected: BigInt,
                  scalar: BigInt = 5, selector: Int = 0, mask: Int = 0x5a,
                  unmasked: Boolean = false, previous: Seq[BigInt] = old): Unit = {
        c.io.ready.expect(true)
        c.io.op.poke(op); c.io.selector.poke(selector); c.io.unmasked.poke(unmasked)
        c.io.v1.poke(pack(b)); c.io.v2.poke(pack(a)); c.io.old.poke(pack(previous))
        c.io.mask.poke(mask); c.io.scalar.poke(scalar); c.io.valid.poke(true)
        c.clock.step(); c.io.valid.poke(false)
        c.io.op.poke(63); c.io.v1.poke(0); c.io.v2.poke(0); c.io.old.poke(0)
        c.io.mask.poke(0); c.io.scalar.poke(0)
        var latency = 0
        while (!c.io.resp.valid.peek().litToBoolean && latency < 200) { c.clock.step(); latency += 1 }
        assert(latency >= 8 && latency < 200)
        for (_ <- 0 until 3) {
          c.io.resp.valid.expect(true); c.io.resp.bits.data.expect(expected)
          c.io.resp.bits.tag.slot.expect(1); c.io.resp.bits.tag.generation.expect(1)
          c.io.resp.bits.seq.expect(7); c.io.resp.bits.nextPc.expect(0x104)
          c.io.resp.bits.exception.expect(false); c.io.ready.expect(false); c.clock.step()
        }
        c.io.resp.ready.poke(true); c.clock.step(); c.io.resp.ready.poke(false)
        c.io.busy.expect(false)
      }
      def merged(values: Seq[BigInt], mask: Int = 0x5a): BigInt =
        pack(values.indices.map(i => if ((mask & (1 << i)) != 0) values(i) else old(i)))
      for (op <- 0 to 21) {
        val values = aa.indices.map { i =>
          val a = aa(i); val b = if ((op & 1) == 1) BigInt(5) else bb(i)
          (op / 2 match {
            case 0 => a + b
            case 1 => a - b
            case 2 => a * b
            case 3 => signed(a).max(signed(b))
            case 4 => signed(a).min(signed(b))
            case 5 => a & b
            case 6 => a | b
            case 7 => a ^ b
            case 8 => a << (b.toInt & 31)
            case 9 => a >> (b.toInt & 31)
            case 10 => signed(a) >> (b.toInt & 31)
          }) & wordMask
        }
        execute(op, aa, bb, merged(values))
      }
      for (op <- 36 to 45) {
        var expected = pack(old) & 255
        for (i <- 0 until 8 if (0x5a & (1 << i)) != 0) {
          val a = aa(i); val b = if ((op & 1) == 1) BigInt(5) else bb(i)
          val bit = (op - 36) / 2 match {
            case 0 => a == b
            case 1 => signed(a) < signed(b)
            case 2 => signed(a) <= signed(b)
            case 3 => a < b
            case 4 => a <= b
          }
          expected = if (bit) expected.setBit(i) else expected.clearBit(i)
        }
        execute(op, aa, bb, expected)
      }
      for (op <- 52 to 55; distance <- Seq(0, 1, 7, 8, 31)) {
        val values = aa.indices.map { i =>
          val index = if (op < 54) i - distance else i + distance
          if (index >= 0 && index < 8) aa(index) else BigInt(0)
        }
        execute(op, aa, bb, merged(values), scalar = distance, selector = distance)
      }
      execute(52, aa, bb, merged(Seq.fill(8)(BigInt(0))), scalar = wordMask)
      execute(56, aa, bb, pack(aa.indices.map(i => if ((0x5a & (1 << i)) != 0) bb(i) else aa(i))))
      val indices = Seq[BigInt](7, 0, 8, wordMask, 4, 7, 2, 1)
      execute(57, aa, indices, merged(indices.map(i => if (i < 8) aa(i.toInt) else BigInt(0))))
      execute(0, aa, bb, pack(aa.zip(bb).map { case (a, b) => a + b }), unmasked = true, mask = 0)
      val two = Seq.fill(8)(BigInt(0x4000)); val one = Seq.fill(8)(BigInt(0x3f80))
      for ((op, answer) <- Seq(22 -> 0x4040, 24 -> 0x3f80, 26 -> 0x4000,
        28 -> 0x4000, 30 -> 0x4040, 32 -> 0x4000, 34 -> 0x3f80); form <- 0 to 1) {
        val previous = if (op == 30) one else old
        val expected = pack((0 until 8).map(i => if ((0x5a & (1 << i)) != 0) BigInt(answer) else previous(i)))
        execute(op + form, two, one, expected, scalar = 0x3f80, previous = previous)
      }
      for (op <- 46 to 51) execute(op, two, one, (pack(old) & 255) & ~BigInt(0x5a), scalar = 0x3f80)
      for ((selector, input, answer) <- Seq((0, BigInt(0x7f81), BigInt(0x7f810000)),
        (1, BigInt(0x3f800000), BigInt(0x3f80)), (2, BigInt(2), BigInt(0x40000000)),
        (3, BigInt(0xc0000000L), wordMask - 1))) {
        execute(58, Seq.fill(8)(input), bb, merged(Seq.fill(8)(answer)), selector = selector)
      }
      val ones32 = Seq.fill(8)(BigInt(0x3f800000))
      val seed = Seq(BigInt(0x40000000)) ++ Seq.fill(7)(BigInt(0))
      def reduced(x: BigInt): BigInt = pack(old.updated(0, x))
      execute(61, ones32, seed, reduced(0x40c00000)) // 2 + four enabled ones
      execute(61, ones32, seed, reduced(0x41200000), unmasked = true)
      execute(61, ones32, seed, reduced(0x40000000), mask = 0)
      execute(62, ones32, seed, reduced(0x40000000))
      execute(62, ones32, seed, reduced(0x40000000), mask = 0)
      val cancellation = Seq[BigInt](0x4b800000, 0x3f800000, 0xcb800000) ++ Seq.fill(5)(BigInt(0))
      execute(61, cancellation, Seq.fill(8)(BigInt(0)), reduced(0), unmasked = true)
      for (delay <- Seq(3, 8)) {
        c.io.op.poke(0); c.io.unmasked.poke(true); c.io.valid.poke(true)
        c.clock.step(); c.io.valid.poke(false); c.clock.step(delay)
        c.io.flush.poke(true); c.io.resp.valid.expect(false); c.clock.step()
        c.io.flush.poke(false); c.io.busy.expect(false); c.io.ready.expect(true)
        c.clock.step(10); c.io.resp.valid.expect(false)
      }
    }
  }
}
