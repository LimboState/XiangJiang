package xiangjiang

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.fu.VectorUnit

/** Supplies captured operands directly, independently of decode and rename. */
class VectorExecutionHarness(p: XJParams) extends Module {
  val io = IO(new Bundle {
    val op = Input(UInt(7.W))
    val selector = Input(UInt(5.W))
    val unmasked = Input(Bool())
    val vl = Input(UInt(32.W))
    val vsew = Input(UInt(2.W))
    val v1 = Input(UInt(128.W))
    val v2 = Input(UInt(128.W))
    val old = Input(UInt(128.W))
    val mask = Input(UInt(128.W))
    val scalar = Input(UInt(32.W))
    val fScalar = Input(UInt(32.W))
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
  unit.io.req.bits.uop.unmasked := io.unmasked
  unit.io.req.bits.uop.vl := io.vl
  unit.io.req.bits.uop.vsew := io.vsew
  unit.io.req.bits.uop.tag.slot := 1.U
  unit.io.req.bits.uop.tag.generation := 1.U
  unit.io.req.bits.uop.seq := 7.U
  unit.io.req.bits.uop.pc := 0x100.U
  unit.io.req.bits.vsrc1.value := io.v1
  unit.io.req.bits.vsrc2.value := io.v2
  unit.io.req.bits.vold.value := io.old
  unit.io.req.bits.vmask.value := io.mask
  unit.io.req.bits.src1.value := io.scalar
  unit.io.req.bits.fsrc1.value := io.fScalar
  unit.io.req.valid := io.valid
  unit.io.flush := io.flush
  io.ready := unit.io.req.ready
  io.busy := unit.io.busy
  io.resp <> unit.io.resp
}

class VectorExecutionSpec extends AnyFunSuite with ChiselSim {
  private val wordMask = (BigInt(1) << 32) - 1
  private def pack(xs: Seq[BigInt]): BigInt =
    xs.zipWithIndex.foldLeft(BigInt(0)) { case (r, (x, i)) => r | ((x & wordMask) << (32 * i)) }
  private def signed(x: BigInt): BigInt = if (x.testBit(31)) x - (BigInt(1) << 32) else x

  test("SEW-controlled integer, permutation, mask, FP32 and reduction execution") {
    simulate(new VectorExecutionHarness(XJParams())) { c =>
      c.io.valid.poke(false)
      c.io.flush.poke(false)
      c.io.resp.ready.poke(false)
      c.io.op.poke(0)
      c.io.selector.poke(0)
      c.io.unmasked.poke(true)
      c.io.vl.poke(4)
      c.io.vsew.poke(2)
      c.io.v1.poke(0)
      c.io.v2.poke(0)
      c.io.old.poke(0)
      c.io.mask.poke(0)
      c.io.scalar.poke(0)
      c.io.fScalar.poke(0)
      c.clock.step()
      val old = (0 until 4).map(i => BigInt("cafebabe", 16) + i)
      val aa = (0 until 4).map(i => (BigInt(i * 19) - 90) & wordMask)
      val bb = (0 until 4).map(i => BigInt(i + 1))
      def execute(op: Int, a: Seq[BigInt], b: Seq[BigInt], expected: BigInt,
                  scalar: BigInt = 5, selector: Int = 0, mask: Int = 0xf,
                  unmasked: Boolean = true, previous: Seq[BigInt] = old,
                  vl: Int = 4, vsew: Int = 2, fScalar: BigInt = 0): Unit = {
        c.io.ready.expect(true)
        c.io.op.poke(op)
        c.io.selector.poke(selector)
        c.io.unmasked.poke(unmasked)
        c.io.vl.poke(vl)
        c.io.vsew.poke(vsew)
        c.io.v1.poke(pack(b))
        c.io.v2.poke(pack(a))
        c.io.old.poke(pack(previous))
        c.io.mask.poke(mask)
        c.io.scalar.poke(scalar)
        c.io.fScalar.poke(fScalar)
        c.io.valid.poke(true)
        c.clock.step()
        c.io.valid.poke(false)
        // Destroy every live input: even aliases must use the pre-remap snapshot.
        c.io.op.poke(63)
        c.io.selector.poke(31)
        c.io.unmasked.poke(!unmasked)
        c.io.v1.poke(0)
        c.io.v2.poke(0)
        c.io.old.poke(0)
        c.io.mask.poke(0)
        c.io.scalar.poke(0)
        c.io.fScalar.poke(0)
        val deadline = 200
        var done = false
        for (_ <- 0 until deadline if !done) {
          done = c.io.resp.valid.peek().litToBoolean
          if (!done) c.clock.step()
        }
        assert(done, "vector completion timeout")
        c.io.resp.bits.exception.expect(false)
        c.io.resp.bits.data.expect(expected)
        assert(c.io.resp.bits.data.peek().litValue == expected)
        for (_ <- 0 until 3) {
          assert(c.io.resp.valid.peek().litToBoolean)
          assert(c.io.resp.bits.data.peek().litValue == expected)
          assert(c.io.resp.bits.tag.slot.peek().litValue == 1)
          assert(c.io.resp.bits.seq.peek().litValue == 7)
          assert(c.io.resp.bits.nextPc.peek().litValue == 0x104)
          c.clock.step()
        }
        c.io.resp.ready.poke(true)
        c.clock.step()
        c.io.resp.ready.poke(false)
        c.io.ready.expect(true)
      }

      // SEW=32 arithmetic.
      execute(VecOp.vadd, aa, bb, pack(Seq(0, 1, 2, 3).map(i => aa(i) + bb(i))))
      execute(VecOp.vsub, aa, bb, pack(Seq(0, 1, 2, 3).map(i => aa(i) - bb(i))))
      execute(VecOp.vmul, aa, bb, pack(Seq(0, 1, 2, 3).map(i => (aa(i) * bb(i)) & wordMask)))
      execute(VecOp.vand, aa, bb, pack(Seq(0, 1, 2, 3).map(i => aa(i) & bb(i))))
      execute(VecOp.vor, aa, bb, pack(Seq(0, 1, 2, 3).map(i => aa(i) | bb(i))))
      execute(VecOp.vxor, aa, bb, pack(Seq(0, 1, 2, 3).map(i => aa(i) ^ bb(i))))
      execute(VecOp.vsll, aa, bb, pack(Seq(0, 1, 2, 3).map(i => (aa(i) << bb(i).toInt) & wordMask)))
      execute(VecOp.vsra, aa, bb, pack(Seq(0, 1, 2, 3).map(i =>
        (signed(aa(i)) >> bb(i).toInt) & wordMask)))
      execute(VecOp.vmax, aa, bb, pack(Seq(0, 1, 2, 3).map(i =>
        (signed(aa(i)).max(signed(bb(i)))) & wordMask)))
      execute(VecOp.vmin, aa, bb, pack(Seq(0, 1, 2, 3).map(i =>
        (signed(aa(i)).min(signed(bb(i)))) & wordMask)))
      // Scalar form: vs2 + x-scalar.
      execute(VecOp.vadd + 1, aa, bb, pack(Seq(0, 1, 2, 3).map(i => aa(i) + 5)))
      // Masked (mask bits 0,2 = 0b0101): inactive elements preserve old.
      execute(VecOp.vadd, aa, bb,
        pack(Seq(aa(0) + bb(0), old(1), aa(2) + bb(2), old(3))),
        mask = 0x5, unmasked = false)
      // Mask comparison result bits: only bits < vl update, the rest keeps old.
      execute(VecOp.vmseq, aa, aa, pack(old) | 0xf,
        mask = 0xf, unmasked = false)
      // Tail: vl=2 keeps elements 2,3.
      execute(VecOp.vadd, aa, bb,
        pack(Seq(aa(0) + bb(0), aa(1) + bb(1), old(2), old(3))), vl = 2)

      // SEW=8: bytes; SEW=16: halfwords (128-bit packed register values).
      def executePacked(op: Int, v1: BigInt, v2: BigInt, expected: BigInt,
                        vl: Int, vsew: Int): Unit = {
        c.io.ready.expect(true)
        c.io.op.poke(op)
        c.io.selector.poke(0)
        c.io.unmasked.poke(true)
        c.io.vl.poke(vl)
        c.io.vsew.poke(vsew)
        c.io.v1.poke(v1)
        c.io.v2.poke(v2)
        c.io.old.poke(0)
        c.io.mask.poke(0)
        c.io.scalar.poke(0)
        c.io.fScalar.poke(0)
        c.io.valid.poke(true)
        c.clock.step()
        c.io.valid.poke(false)
        val deadline = 200
        var done = false
        for (_ <- 0 until deadline if !done) {
          done = c.io.resp.valid.peek().litToBoolean
          if (!done) c.clock.step()
        }
        assert(done, "vector completion timeout")
        c.io.resp.bits.exception.expect(false)
        c.io.resp.bits.data.expect(expected)
        c.io.resp.ready.poke(true)
        c.clock.step()
        c.io.resp.ready.poke(false)
        c.io.ready.expect(true)
      }
      val b8 = (0 until 16).map(i => BigInt(i * 3 & 0xff))
      val b8b = (0 until 16).map(i => BigInt(7))
      executePacked(VecOp.vadd, VectorEncoding.packed8(b8b), VectorEncoding.packed8(b8),
        VectorEncoding.packed8((0 until 16).map(i => BigInt(((i * 3 + 7) & 0xff)))),
        vl = 16, vsew = 0)
      val h16 = (0 until 8).map(i => BigInt((i * 1000) & 0xffff))
      val h16b = (0 until 8).map(i => BigInt(1))
      executePacked(VecOp.vsub, VectorEncoding.packed16(h16b), VectorEncoding.packed16(h16),
        VectorEncoding.packed16((0 until 8).map(i => BigInt((i * 1000 - 1) & 0xffff))),
        vl = 8, vsew = 1)

      // vmerge: mask selects vs1 vs vs2; vrgather: indices from vs1.
      execute(VecOp.vmerge, aa, bb, pack(Seq(
        bb(0), aa(1), bb(2), aa(3))), mask = 0x5, unmasked = false)
      execute(VecOp.vrgather, bb, Seq(3, 0, 1, 2),
        pack(Seq(bb(3), bb(0), bb(1), bb(2))))
      // vslideup with distance 2: elements 0,1 zeroed, 2,3 take vs2[0,1].
      execute(VecOp.vslideup, aa, bb, pack(Seq(BigInt(0), BigInt(0), aa(0), aa(1))),
        scalar = 2)
      // vslidedown with distance 2: elements 0,1 take vs2[2,3].
      execute(VecOp.vslidedown, aa, bb, pack(Seq(aa(2), aa(3), old(2), old(3))),
        scalar = 2)

      // Legacy BF16 arithmetic: 8 lanes in the low 128 bits.
      val bf = Seq(0x3f80, 0x4000, 0x3f00, 0x4040, 0x0001, 0x7f7f, 0x3580, 0xbf80)
      val bf2 = Seq(0x3f80, 0x3f00, 0x4000, 0x3e80, 0x3f00, 0x3f80, 0x3f80, 0x3f80)
      def bf16Bits(x: BigInt): Float = java.lang.Float.intBitsToFloat((x << 16).toInt)
      def bf16Of(f: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(f) >>> 16)
      executePacked(VecOp.vfadd, VectorEncoding.packed16(bf.map(BigInt(_))),
        VectorEncoding.packed16(bf2.map(BigInt(_))),
        VectorEncoding.packed16((0 until 8).map(i =>
          bf16Of(bf16Bits(BigInt(bf2(i))) + bf16Bits(BigInt(bf(i)))))),
        vl = 16, vsew = 0)

      // FP32 vector ops (SEW=32 required).
      val f32 = Seq(0x3f800000L, 0x40000000L, 0xc0000000L, 0x00000001L).map(BigInt(_))
      val f32b = Seq(0x40000000L, 0x3f000000L, 0x3f800000L, 0x00000001L).map(BigInt(_))
      def fAdd(a: BigInt, b: BigInt): BigInt =
        BigInt(java.lang.Float.floatToRawIntBits(
          java.lang.Float.intBitsToFloat(a.toInt) + java.lang.Float.intBitsToFloat(b.toInt)) & wordMask.toLong)
      execute(VecOp.vfadd32, f32, f32b, pack((0 until 4).map(i => fAdd(f32(i), f32b(i)))))
      def fMul(a: BigInt, b: BigInt): BigInt =
        BigInt(java.lang.Float.floatToRawIntBits(
          java.lang.Float.intBitsToFloat(a.toInt) * java.lang.Float.intBitsToFloat(b.toInt)) & wordMask.toLong)
      execute(VecOp.vfmul32, f32, f32b, pack((0 until 4).map(i => fMul(f32(i), f32b(i)))))
      // Removed fused ops lower to explicit multiply/add (two roundings).
      val products = (0 until 4).map(i => fMul(f32(i), f32b(i)))
      execute(VecOp.vfadd32, products, old, pack((0 until 4).map(i => fAdd(products(i), old(i)))))
      // Comparisons produce mask bits (rest of the register preserved).
      execute(VecOp.vfeq32, f32, f32, pack(old) | 0xf,
        mask = 0xf, unmasked = false)
      // FP32 reduction: sum of active elements lands in data[31:0].
      val fsum = fAdd(fAdd(fAdd(f32(0), f32(1)), f32(2)), f32(3))
      execute(VecOp.vfredsum32, f32, bb, fsum,
        previous = Seq.fill(4)(BigInt(0)))
      execute(VecOp.vfredsum32, f32, bb, fAdd(f32(0), f32(2)),
        mask = 5, unmasked = false)
      execute(VecOp.vfredsum32, f32, bb, BigInt(0), vl = 0)
      execute(VecOp.vfredsum32, f32, bb, BigInt(0), mask = 0, unmasked = false)
      // FCVT: FP32 → INT32 (RNE) and back.
      execute(VecOp.vfcvtF2I32, f32, bb, pack(Seq(1, 2, -2, 0).map(BigInt(_))),
        previous = Seq.fill(4)(BigInt(0)))
      execute(VecOp.vfcvtI2F32, Seq(3, -7, 1024, 0).map(BigInt(_)), bb,
        pack(Seq(0x40400000L, 0xc0e00000L, 0x44800000L, 0).map(BigInt(_))),
        previous = Seq.fill(4)(BigInt(0)))

      // Cancel selection, arithmetic, merge, finalization and a stalled result.
      // Restart immediately so stale arithmetic pipeline contents must not leak.
      for (age <- Seq(0, 1, 5, 6, 7, 8, 127, 128, 129, 132)) {
        c.io.op.poke(VecOp.vfadd32); c.io.vsew.poke(2); c.io.vl.poke(4)
        c.io.v1.poke(pack(f32)); c.io.v2.poke(pack(f32b))
        c.io.unmasked.poke(true); c.io.valid.poke(true)
        assert(c.io.ready.peek().litToBoolean)
        c.clock.step(); c.io.valid.poke(false); c.clock.step(age)
        c.io.flush.poke(true)
        assert(!c.io.resp.valid.peek().litToBoolean)
        assert(!c.io.ready.peek().litToBoolean)
        c.clock.step(); c.io.flush.poke(false)
        assert(!c.io.busy.peek().litToBoolean)
        execute(VecOp.vfadd32, f32b, f32b,
          pack((0 until 4).map(i => fAdd(f32b(i), f32b(i)))))
      }
    }
  }
}
