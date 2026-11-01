package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.fu.VectorFloat

class Float32Harness extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W))
    val c = Input(UInt(32.W))
    val rm = Input(UInt(3.W))
    val addResult = Output(UInt(32.W))
    val addFlags = Output(UInt(5.W))
    val mulResult = Output(UInt(32.W))
    val mulFlags = Output(UInt(5.W))
    val divResult = Output(UInt(32.W))
    val divFlags = Output(UInt(5.W))
    val sqrtResult = Output(UInt(32.W))
    val sqrtFlags = Output(UInt(5.W))
    val fmaResult = Output(UInt(32.W))
    val fmaFlags = Output(UInt(5.W))
    val eq = Output(Bool())
    val lt = Output(Bool())
    val le = Output(Bool())
    val cmpNv = Output(Bool())
    val f2iResult = Output(UInt(32.W))
    val f2iFlags = Output(UInt(5.W))
    val f2uResult = Output(UInt(32.W))
    val f2uFlags = Output(UInt(5.W))
    val i2fResult = Output(UInt(32.W))
    val i2fFlags = Output(UInt(5.W))
    val u2fResult = Output(UInt(32.W))
    val u2fFlags = Output(UInt(5.W))
  })
  private def drive(dst: (UInt, UInt), src: (UInt, UInt)): Unit = { dst._1 := src._1; dst._2 := src._2 }
  drive(io.addResult -> io.addFlags, VectorFloat.f32Add(io.a, io.b, io.rm))
  drive(io.mulResult -> io.mulFlags, VectorFloat.f32Mul(io.a, io.b, io.rm))
  drive(io.divResult -> io.divFlags, VectorFloat.f32Div(io.a, io.b, io.rm))
  drive(io.sqrtResult -> io.sqrtFlags, VectorFloat.f32Sqrt(io.a, io.rm))
  drive(io.fmaResult -> io.fmaFlags, VectorFloat.f32Fma(io.a, io.b, io.c, io.rm))
  val (eqR, nvE) = VectorFloat.f32Cmp(io.a, io.b, 0.U)
  val (ltR, nvL) = VectorFloat.f32Cmp(io.a, io.b, 1.U)
  val (leR, nvLe) = VectorFloat.f32Cmp(io.a, io.b, 2.U)
  io.eq := eqR
  io.lt := ltR
  io.le := leR
  io.cmpNv := nvE || nvL || nvLe
  drive(io.f2iResult -> io.f2iFlags, VectorFloat.f32ToInt(io.a, io.rm, false))
  drive(io.f2uResult -> io.f2uFlags, VectorFloat.f32ToInt(io.a, io.rm, true))
  drive(io.i2fResult -> io.i2fFlags, VectorFloat.intToF32(io.a, io.rm, false))
  drive(io.u2fResult -> io.u2fFlags, VectorFloat.intToF32(io.a, io.rm, true))
}

class Float32Spec extends AnyFunSuite with ChiselSim {
  private def bits(f: Float): BigInt =
    BigInt(java.lang.Float.floatToRawIntBits(f)) & BigInt("ffffffff", 16)
  private def fl(b: BigInt): Float = java.lang.Float.intBitsToFloat(b.toInt)

  test("FP32 scalar ops match host arithmetic and report flags on special values") {
    simulate(new Float32Harness) { c =>
      c.io.a.poke(0); c.io.b.poke(0); c.io.c.poke(0); c.io.rm.poke(0)
      c.clock.step()
      def check(a: Float, b: Float, rm: Int = 0): Unit = {
        c.io.a.poke(bits(a)); c.io.b.poke(bits(b)); c.io.rm.poke(rm)
        c.clock.step()
        val wantAdd = bits(a + b)
        val wantMul = bits(a * b)
        val wantDiv = if (b == 0.0f) bits(if (a == 0.0f || a.isNaN) Float.NaN
        else Float.PositiveInfinity * math.signum(a)) else bits(a / b)
        c.io.addResult.expect(wantAdd)
        c.io.mulResult.expect(wantMul)
        c.io.divResult.expect(wantDiv)
        c.io.eq.expect(a == b)
        c.io.lt.expect(a < b)
        c.io.le.expect(a <= b)
      }
      // Exact finite arithmetic and IEEE comparisons.
      check(1.0f, 2.0f)
      check(-3.5f, 0.25f)
      check(1024.0f, 0.0625f)
      check(1.0f, 3.0f) // 1/3 inexact -> NX only
      c.io.divFlags.expect(1)
      // Signed zero and infinity behavior.
      c.io.a.poke(bits(0.0f)); c.io.b.poke(bits(-0.0f)); c.io.rm.poke(0)
      c.clock.step()
      c.io.addResult.expect(0) // +0 + -0 = +0 (RNE)
      c.io.eq.expect(true)
      c.io.a.poke(bits(Float.PositiveInfinity)); c.io.b.poke(bits(Float.NegativeInfinity))
      c.clock.step()
      c.io.addResult.expect(0x7fc00000L) // inf + -inf -> canonical NaN
      c.io.addFlags.expect(16) // NV
      // Division special cases.
      c.io.a.poke(bits(1.0f)); c.io.b.poke(bits(0.0f)); c.clock.step()
      c.io.divResult.expect(0x7f800000L)
      c.io.divFlags.expect(8) // DZ
      c.io.a.poke(bits(0.0f)); c.clock.step()
      c.io.divResult.expect(0x7fc00000L)
      c.io.divFlags.expect(16) // NV
      // Multiplication overflow/underflow.
      c.io.a.poke(bits(Float.MaxValue)); c.io.b.poke(bits(2.0f)); c.clock.step()
      c.io.mulResult.expect(0x7f800000L)
      c.io.mulFlags.expect(4 | 1) // OF | NX
      c.io.a.poke(1); c.io.b.poke(1); c.clock.step() // min subnormal * min subnormal
      c.io.mulResult.expect(0)
      c.io.mulFlags.expect(2 | 1) // UF | NX
      // sqrt specials.
      c.io.a.poke(bits(4.0f)); c.clock.step()
      c.io.sqrtResult.expect(bits(2.0f))
      c.io.a.poke(bits(2.0f)); c.clock.step()
      c.io.sqrtResult.expect(bits(Math.sqrt(2.0).toFloat))
      c.io.sqrtFlags.expect(1) // NX
      c.io.a.poke(bits(-1.0f)); c.clock.step()
      c.io.sqrtResult.expect(0x7fc00000L)
      c.io.sqrtFlags.expect(16) // NV
      c.io.a.poke(bits(Float.PositiveInfinity)); c.clock.step()
      c.io.sqrtResult.expect(0x7f800000L)
      // FMA with exact products; FMA of inf and zero is invalid.
      c.io.a.poke(bits(2.0f)); c.io.b.poke(bits(3.0f)); c.io.c.poke(bits(1.0f)); c.clock.step()
      c.io.fmaResult.expect(bits(7.0f))
      c.io.a.poke(bits(Float.PositiveInfinity)); c.io.b.poke(bits(0.0f)); c.clock.step()
      c.io.fmaResult.expect(0x7fc00000L)
      c.io.fmaFlags.expect(16)
      // Rounding modes: 1.5 + 2^-24 ties.
      c.io.a.poke(bits(1.5f)); c.io.b.poke(0x33800000L); c.io.rm.poke(0); c.clock.step()
      c.io.addResult.expect(bits(1.5f)) // tie to even: 1.5 mantissa LSB is 0
      c.io.rm.poke(4); c.clock.step()
      c.io.addResult.expect(bits(Math.nextUp(1.5f))) // ties away
      c.io.rm.poke(1); c.clock.step() // RTZ
      c.io.addResult.expect(bits(1.5f))
      c.io.rm.poke(3); c.clock.step() // RUP
      c.io.addResult.expect(bits(Math.nextUp(1.5f)))
      c.io.rm.poke(2); c.clock.step() // RDN
      c.io.addResult.expect(bits(1.5f))
      // Negative: RDN rounds away from zero, RUP toward zero.
      c.io.a.poke(bits(-1.5f)); c.io.b.poke(0xb3800000L); c.io.rm.poke(0); c.clock.step()
      c.io.addResult.expect(bits(-1.5f))
      c.io.rm.poke(2); c.clock.step()
      c.io.addResult.expect(bits(-Math.nextUp(1.5f)))
      c.io.rm.poke(3); c.clock.step()
      c.io.addResult.expect(bits(-1.5f))
      // Conversions.
      c.io.rm.poke(0)
      c.io.a.poke(bits(7.75f)); c.clock.step()
      c.io.f2iResult.expect(8) // RNE up
      c.io.rm.poke(1); c.clock.step() // RTZ
      c.io.f2iResult.expect(7)
      c.io.rm.poke(2); c.clock.step() // RDN
      c.io.f2iResult.expect(7)
      c.io.a.poke(bits(-7.75f)); c.io.rm.poke(0); c.clock.step()
      c.io.f2iResult.expect(BigInt("fffffff8", 16))
      c.io.f2iFlags.expect(1) // NX
      c.io.rm.poke(2); c.clock.step() // RDN
      c.io.f2iResult.expect(BigInt("fffffff8", 16))
      c.io.a.poke(bits(Float.NaN)); c.clock.step()
      c.io.f2iResult.expect(0x7fffffffL)
      c.io.f2iFlags.expect(16) // NV
      c.io.a.poke(bits(Float.PositiveInfinity)); c.clock.step()
      c.io.f2iResult.expect(0x7fffffffL)
      c.io.a.poke(bits(-2.0f)); c.clock.step()
      c.io.f2uResult.expect(0) // negative -> 0 with NV
      c.io.f2uFlags.expect(16)
      c.io.a.poke(bits(Float.NaN)); c.clock.step()
      c.io.f2uResult.expect(BigInt("ffffffff", 16))
      c.io.a.poke(7); c.io.rm.poke(0); c.clock.step()
      c.io.i2fResult.expect(bits(7.0f))
      c.io.a.poke(BigInt("80000000", 16)); c.clock.step()
      c.io.i2fResult.expect(bits(-2147483648.0f))
      c.io.a.poke(0xfffffffeL); c.clock.step()
      c.io.u2fResult.expect(bits(4294967294.0f))
      // NaN canonicalization and comparison invalid flag.
      c.io.a.poke(0x7fc00000L); c.io.b.poke(0x7fc00000L); c.io.rm.poke(0); c.clock.step()
      c.io.eq.expect(false); c.io.lt.expect(false); c.io.le.expect(false)
      c.io.cmpNv.expect(false) // quiet NaN does not raise NV
      c.io.a.poke(0x7f800001L); c.clock.step() // signaling NaN
      c.io.cmpNv.expect(true)
    }
  }
}
