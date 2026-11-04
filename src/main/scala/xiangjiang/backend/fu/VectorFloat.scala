package xiangjiang.backend.fu

import chisel3._
import chisel3.util._

/** Combinational IEEE binary arithmetic, RNE with gradual underflow and no flags.
  * Alignment retains guard/round/sticky bits; FMA retains the full product until
  * its sole rounding. Datapaths scale with precision, not the exponent range.
  */
object VectorFloat {
  private val bias = 127
  private val emin = -126

  private def nan(p: Int): UInt = ((BigInt(255) << (p - 1)) | (BigInt(1) << (p - 2))).U((p + 8).W)
  private def infinity(sign: Bool, p: Int): UInt = Cat(sign, 255.U(8.W), 0.U((p - 1).W))
  private def zero(sign: Bool, p: Int): UInt = Cat(sign, 0.U((p + 7).W))

  private class Parts(x: UInt, val p: Int) {
    val sign = x(p + 7)
    val fraction = x(p - 2, 0)
    val exp = x(p + 6, p - 1)
    val isNaN = exp.andR && fraction.orR
    val isInf = exp.andR && !fraction.orR
    val isZero = !exp.orR && !fraction.orR
    val sig = Cat(exp.orR, fraction)
    val leading = PriorityEncoder(Reverse(sig))
    val normalized = (sig << leading)(p - 1, 0)
    val exponent = Mux(exp.orR, exp, 1.U).zext - bias.S(12.W) - leading.zext
  }

  /** All discarded bits, including shifts wider than x, are folded into bit 0. */
  private def jam(x: UInt, distance: UInt): UInt = {
    val w = x.getWidth
    val shift = Mux(distance >= w.U, w.U, distance)(log2Ceil(w + 1) - 1, 0)
    val shifted = x >> shift
    val restored = (shifted << shift)(w - 1, 0)
    shifted | (x =/= restored).asUInt
  }

  private def stage[T <: Data](x: T, pipelined: Boolean): T = {
    if (pipelined) {
      val registered = Reg(chiselTypeOf(x))
      registered := x
      registered
    } else x
  }
  private def delay[T <: Data](x: T, cycles: Int, pipelined: Boolean): T =
    (0 until cycles).foldLeft(x)((v, _) => stage(v, pipelined))

  /** Round once, optionally with two internal register boundaries. */
  private def round(magnitude: UInt, scale: SInt, inputSign: Bool, p: Int,
      pipelined: Boolean = false): UInt = {
    val w = magnitude.getWidth
    require(w >= p + 3)
    val leading = PriorityEncoder(Reverse(magnitude))
    val normalized = stage((magnitude << leading)(w - 1, 0), pipelined)
    val exponent = stage(scale + (w - 1).S(12.W) - leading.zext, pipelined)
    val underflow = Mux(exponent < emin.S, emin.S(12.W) - exponent, 0.S(12.W))
    val shifted = stage(jam(normalized, ((w - p - 3).S(12.W) + underflow).asUInt), pipelined)
    val sign = delay(inputSign, 2, pipelined)
    val nonzero = delay(magnitude.orR, 2, pipelined)
    val kept = shifted(p + 2, 3)
    val increment = shifted(2) && (shifted(1, 0).orR || kept(0))
    val rounded = kept +& increment
    val effectiveExponent = stage(Mux(exponent < emin.S, emin.S(12.W), exponent), pipelined)
    val finalExponent = effectiveExponent + rounded(p).asUInt.zext
    val fraction = Mux(rounded(p), rounded(p - 1, 1), rounded(p - 2, 0))
    val biased = (finalExponent + bias.S(12.W)).asUInt
    val expField = Mux(rounded(p) || rounded(p - 1), biased(7, 0), 0.U(8.W))
    Mux(!nonzero, zero(sign, p),
      Mux(finalExponent > 127.S, infinity(sign, p), Cat(sign, expField, fraction)))
  }

  /** Inputs have their leading bit at w-2 and an unbiased exponent. */
  private def sumMagnitudes(a: UInt, ae: SInt, as: Bool,
                            b: UInt, be: SInt, bs: Bool, p: Int,
                            pipelined: Boolean = false): UInt = {
    val w = a.getWidth
    val common = Mux(ae >= be, ae, be)
    val ax = stage(jam(a, (common - ae).asUInt), pipelined)
    val bx = stage(jam(b, (common - be).asUInt), pipelined)
    val sa = stage(as, pipelined)
    val sb = stage(bs, pipelined)
    val aLarger = ax >= bx
    val magnitude = Mux(sa === sb, ax + bx, Mux(aLarger, ax - bx, bx - ax))
    val sign = Mux(!magnitude.orR, sa && sb, Mux(sa === sb || aLarger, sa, sb))
    round(stage(magnitude, pipelined), delay(common - (w - 2).S(12.W), 2, pipelined),
      stage(sign, pipelined), p, pipelined)
  }

  private def addGeneric(a: UInt, b: UInt, p: Int, pipelined: Boolean = false): UInt = {
    val x = new Parts(a, p)
    val y = new Parts(b, p)
    val w = p + 5
    val ax = Cat(0.U(1.W), x.normalized, 0.U((w - p - 1).W))
    val bx = Cat(0.U(1.W), y.normalized, 0.U((w - p - 1).W))
    // Zero's arbitrary decoded exponent must not shift a tiny nonzero operand.
    val ae = Mux(x.isZero, (-512).S(12.W), x.exponent)
    val be = Mux(y.isZero, (-512).S(12.W), y.exponent)
    val finite = sumMagnitudes(ax, ae, x.sign, bx, be, y.sign, p, pipelined)
    Mux(delay(x.isNaN || y.isNaN || (x.isInf && y.isInf && x.sign =/= y.sign), 4, pipelined), nan(p),
      Mux(delay(x.isInf, 4, pipelined), infinity(delay(x.sign, 4, pipelined), p),
        Mux(delay(y.isInf, 4, pipelined), infinity(delay(y.sign, 4, pipelined), p), finite)))
  }

  def add(a: UInt, b: UInt): UInt = addGeneric(a(15, 0), b(15, 0), 8)
  def sub(a: UInt, b: UInt): UInt = add(a, Cat(!b(15), b(14, 0)))
  def add32(a: UInt, b: UInt): UInt = addGeneric(a(31, 0), b(31, 0), 24)
  def add32Pipelined(a: UInt, b: UInt): UInt = addGeneric(a(31, 0), b(31, 0), 24, true)
  def fmaPipelined(a: UInt, b: UInt, c: UInt): UInt = fmaGeneric(a, b, c, 8, true)

  val addPipelineStages = 4
  val mulPipelineStages = 4
  def addPipelined(a: UInt, b: UInt): UInt = addGeneric(a(15, 0), b(15, 0), 8, true)
  def subPipelined(a: UInt, b: UInt): UInt = addPipelined(a, Cat(!b(15), b(14, 0)))
  def mulPipelined(a: UInt, b: UInt): UInt = mulGeneric(a(15, 0), b(15, 0), 8, true)

  private def mulGeneric(a: UInt, b: UInt, p: Int, pipelined: Boolean = false): UInt = {
    val x = new Parts(a, p)
    val y = new Parts(b, p)
    val sign = x.sign ^ y.sign
    val product = stage(stage(x.normalized, pipelined) * stage(y.normalized, pipelined), pipelined)
    val finite = round(Cat(product, 0.U(3.W)),
      delay(x.exponent + y.exponent - (2 * p + 1).S(12.W), 2, pipelined),
      delay(sign, 2, pipelined), p, pipelined)
    Mux(delay(x.isNaN || y.isNaN || (x.isInf && y.isZero) || (y.isInf && x.isZero), 4, pipelined), nan(p),
      Mux(delay(x.isInf || y.isInf, 4, pipelined), infinity(delay(sign, 4, pipelined), p), finite))
  }

  def mul(a: UInt, b: UInt): UInt = mulGeneric(a(15, 0), b(15, 0), 8)
  def mul32(a: UInt, b: UInt): UInt = mulGeneric(a(31, 0), b(31, 0), 24)

  private def divGeneric(a: UInt, b: UInt, p: Int): UInt = {
    val x = new Parts(a, p)
    val y = new Parts(b, p)
    val sign = x.sign ^ y.sign
    val fractional = p + 4
    val numerator = Cat(x.normalized, 0.U(fractional.W))
    val denominator = Mux(y.isZero, 1.U(p.W), y.normalized)
    // Normalized operands bound the quotient to p+5 bits. Recover only the
    // remainder's nonzero flag, avoiding a second divider for modulo.
    val quotient = (numerator / denominator)(fractional, 0)
    val inexact = numerator =/= quotient * denominator
    val finite = round(quotient | inexact.asUInt,
      x.exponent - y.exponent - fractional.S(12.W), sign, p)
    Mux(x.isNaN || y.isNaN || (x.isZero && y.isZero) || (x.isInf && y.isInf), nan(p),
      Mux(x.isInf || y.isZero, infinity(sign, p),
        Mux(x.isZero || y.isInf, zero(sign, p), finite)))
  }

  /** Shared classification/rounding for the iterative BF16 divider. */
  def divisionOperands(a: UInt, b: UInt): (UInt, UInt) = {
    val x = new Parts(a, 8); val y = new Parts(b, 8)
    (Cat(x.normalized, 0.U(12.W)), Mux(y.isZero, 1.U(8.W), y.normalized))
  }
  def divisionResult(a: UInt, b: UInt, quotient: UInt, remainder: UInt): UInt = {
    val x = new Parts(a, 8); val y = new Parts(b, 8)
    val sign = x.sign ^ y.sign
    val finite = round(quotient(12, 0) | remainder.orR.asUInt,
      x.exponent - y.exponent - 12.S(12.W), sign, 8)
    Mux(x.isNaN || y.isNaN || (x.isZero && y.isZero) || (x.isInf && y.isInf), nan(8),
      Mux(x.isInf || y.isZero, infinity(sign, 8), Mux(x.isZero || y.isInf, zero(sign, 8), finite)))
  }

  def div(a: UInt, b: UInt): UInt = divGeneric(a(15, 0), b(15, 0), 8)
  def div32(a: UInt, b: UInt): UInt = divGeneric(a(31, 0), b(31, 0), 24)

  def fma(a: UInt, b: UInt, c: UInt): UInt = fmaGeneric(a, b, c, 8)
  def fma32(a: UInt, b: UInt, c: UInt): UInt = fmaGeneric(a, b, c, 24)

  // Six internal register boundaries, followed by the caller's result register.
  // No intermediate product rounding: identical arithmetic to fma32.
  val fma32PipelineStages = 6
  def fma32Pipelined(a: UInt, b: UInt, c: UInt): UInt =
    fmaGeneric(a, b, c, 24, pipelined = true)

  private def fmaGeneric(a: UInt, b: UInt, c: UInt, p: Int,
      pipelined: Boolean = false): UInt = {
    val x = new Parts(stage(a, pipelined), p)
    val y = new Parts(stage(b, pipelined), p)
    val z = new Parts(stage(c, pipelined), p)
    val ps = stage(x.sign ^ y.sign, pipelined)
    val product = stage(x.normalized * y.normalized, pipelined)
    val high = product(2 * p - 1)
    val pm = Mux(high, Cat(0.U(1.W), product, 0.U(4.W)),
      Cat(0.U(1.W), product(2 * p - 2, 0), 0.U(5.W)))
    val cm = stage(Cat(0.U(1.W), z.normalized, 0.U((p + 4).W)), pipelined)
    val productZero = stage(x.isZero || y.isZero, pipelined)
    val productExponent = stage(x.exponent + y.exponent, pipelined)
    val pe = Mux(productZero, (-512).S(12.W), productExponent + high.asUInt.zext)
    val ce = stage(Mux(z.isZero, (-512).S(12.W), z.exponent), pipelined)
    val finite = sumMagnitudes(pm, pe, ps, cm, ce, stage(z.sign, pipelined), p, pipelined)
    val invalidProduct = (x.isInf && y.isZero) || (y.isInf && x.isZero)
    val productInf = x.isInf || y.isInf
    val productSign = x.sign ^ y.sign
    val special = Mux(x.isNaN || y.isNaN || z.isNaN || invalidProduct ||
      (productInf && z.isInf && productSign =/= z.sign), nan(p),
      Mux(productInf, infinity(productSign, p), infinity(z.sign, p)))
    Mux(delay(x.isNaN || y.isNaN || z.isNaN || invalidProduct || productInf || z.isInf,
      5, pipelined), delay(special, 5, pipelined), finite)
  }

  def eq(a: UInt, b: UInt): Bool = {
    val x = new Parts(a, 8)
    val y = new Parts(b, 8)
    !x.isNaN && !y.isNaN && (a(15, 0) === b(15, 0) || (x.isZero && y.isZero))
  }

  def lt(a: UInt, b: UInt): Bool = {
    val x = new Parts(a, 8)
    val y = new Parts(b, 8)
    !x.isNaN && !y.isNaN && !(x.isZero && y.isZero) &&
      Mux(x.sign =/= y.sign, x.sign, Mux(x.sign, a(14, 0) > b(14, 0), a(14, 0) < b(14, 0)))
  }

  def le(a: UInt, b: UInt): Bool = lt(a, b) || eq(a, b)

  private def minMax(a: UInt, b: UInt, maximum: Boolean): UInt = {
    val x = new Parts(a, 8)
    val y = new Parts(b, 8)
    val zeroSign = if (maximum) x.sign && y.sign else x.sign || y.sign
    val ordered = if (maximum) Mux(lt(a, b), b(15, 0), a(15, 0))
      else Mux(lt(a, b), a(15, 0), b(15, 0))
    Mux(x.isNaN && y.isNaN, nan(8), Mux(x.isNaN, b(15, 0), Mux(y.isNaN, a(15, 0),
      Mux(x.isZero && y.isZero, zero(zeroSign, 8), ordered))))
  }

  def min(a: UInt, b: UInt): UInt = minMax(a, b, maximum = false)
  def max(a: UInt, b: UInt): UInt = minMax(a, b, maximum = true)

  /** Combinational digit-by-digit integer square root. */
  private def integerSqrt(n: UInt): UInt = {
    val width = n.getWidth
    val pairs = (width + 1) / 2
    val rootWidth = pairs + 1
    var remainder: UInt = 0.U((width + 2).W)
    var root: UInt = 0.U(rootWidth.W)
    for (i <- (pairs - 1) to 0 by -1) {
      val lo = 2 * i
      val bits: UInt =
        if (lo + 1 < width) n(lo + 1, lo)
        else if (lo < width) Cat(0.U(1.W), n(lo))
        else 0.U(2.W)
      val shifted = Cat(remainder, bits)
      val rootShifted = (root << 1)(rootWidth - 1, 0)
      val trial = (rootShifted + 1.U).pad(width + 4)
      val ge = shifted >= trial
      remainder = Mux(ge, shifted - trial, shifted)(width + 1, 0)
      root = Mux(ge, rootShifted + 2.U, rootShifted)(rootWidth - 1, 0)
    }
    (root >> 1)(pairs - 1, 0)
  }

  /** FP32 square root with one final RNE rounding to FP32. */
  def sqrt32(a: UInt): UInt = {
    val x = new Parts(a(31, 0), 24)
    val q = x.exponent - 23.S(12.W)
    val oddExponent = q(0)
    val nPrime = Mux(oddExponent, Cat(x.normalized, 0.U(1.W)),
      Cat(0.U(1.W), x.normalized))
    val radicand = Cat(nPrime, 0.U(20.W))
    val root = integerSqrt(radicand)
    val sticky = (root * root) =/= radicand
    // root*2^20 approximates sqrt(nPrime); the low four guard bits widen the
    // magnitude enough for round() and fold the remainder into bit zero.
    val magnitude = Cat(root, 0.U(4.W)) | sticky.asUInt
    val finite = round(magnitude, (q >> 1) - 14.S(12.W), false.B, 24)
    Mux(x.isNaN, nan(24),
      Mux(x.isInf, Mux(x.sign, nan(24), infinity(false.B, 24)),
        Mux(x.isZero, zero(x.sign, 24), Mux(x.sign, nan(24), finite))))
  }

  def rsqrt32(a: UInt): UInt = div32("h3f800000".U(32.W), sqrt32(a))

  private def fp32(x: Float): UInt =
    BigInt(java.lang.Float.floatToRawIntBits(x) & 0xffffffffL).U(32.W)

  /** exp(x) for FP32, using range reduction and a fifth-order polynomial. */
  def exp32(a: UInt): UInt = {
    val x = new Parts(a(31, 0), 24)
    val log2e = fp32(1.4426950408889634f)
    val ln2 = fp32(-0.6931471805599453f)
    val kBits = toInt(mul32(a(31, 0), log2e))
    val k = kBits.asSInt
    val kf = fromInt(kBits)
    val r = add32(a(31, 0), mul32(kf, ln2))
    var acc = fp32(1.0f / 720.0f)
    acc = add32(mul32(acc, r), fp32(1.0f / 120.0f))
    acc = add32(mul32(acc, r), fp32(1.0f / 24.0f))
    acc = add32(mul32(acc, r), fp32(1.0f / 6.0f))
    acc = add32(mul32(acc, r), fp32(0.5f))
    acc = add32(mul32(acc, r), fp32(1.0f))
    acc = add32(mul32(acc, r), fp32(1.0f))
    def pow2(exponent: SInt): UInt =
      Cat(0.U(1.W), (127.S(10.W) + exponent)(7, 0), 0.U(23.W))
    val scale = Mux(k < (-126).S,
      mul32(fp32(1.17549435e-38f), pow2(k + 126.S)),
      pow2(k))
    val finite = mul32(acc, scale)
    Mux(x.isNaN, nan(24),
      Mux(x.isInf, Mux(x.sign, zero(false.B, 24), infinity(false.B, 24)),
        Mux(x.isZero, fp32(1.0f),
          Mux(k > 127.S, infinity(false.B, 24),
            Mux(k < (-149).S, zero(false.B, 24), finite)))))
  }

  def exp(a: UInt): UInt = narrow(exp32(widen(a(15, 0))))
  def rcp(a: UInt): UInt = div("h3f80".U(16.W), a(15, 0))
  def rsqrt(a: UInt): UInt = narrow(rsqrt32(widen(a(15, 0))))

  // Widening is a bit-preserving conversion, including signaling NaN payloads.
  def widen(a: UInt): UInt = Cat(a(15, 0), 0.U(16.W))

  def max32(a: UInt, b: UInt): UInt = {
    val x = new Parts(a, 24)
    val y = new Parts(b, 24)
    val less = Mux(x.sign =/= y.sign, x.sign,
      Mux(x.sign, a(30, 0) > b(30, 0), a(30, 0) < b(30, 0)))
    Mux(x.isNaN && y.isNaN, nan(24), Mux(x.isNaN, b, Mux(y.isNaN, a,
      Mux(x.isZero && y.isZero, zero(x.sign && y.sign, 24), Mux(less, b, a)))))
  }

  def narrow(a: UInt): UInt = {
    val x = new Parts(a, 24)
    val finite = round(Cat(x.normalized, 0.U(3.W)), x.exponent - 26.S(12.W), x.sign, 8)
    Mux(x.isNaN, nan(8), Mux(x.isInf, infinity(x.sign, 8), finite))
  }

  def fromInt(a: UInt): UInt = {
    val sign = a(31)
    val magnitude = Mux(sign, (~a(31, 0)).asUInt + 1.U, a(31, 0))
    round(Cat(magnitude, 0.U(3.W)), (-3).S(12.W), sign, 24)
  }

  def toInt(a: UInt): UInt = {
    val x = new Parts(a, 24)
    val source = Cat(0.U(8.W), x.normalized, 0.U(3.W))
    val right = 23.S(12.W) - x.exponent
    val left = Mux(right < 0.S, -right, 0.S(12.W)).asUInt
    val leftBounded = Mux(left > 31.U, 31.U, left)(4, 0)
    val shifted = Mux(right >= 0.S, jam(source, right.asUInt), (source << leftBounded)(34, 0))
    val kept = shifted(34, 3)
    val rounded = kept +& (shifted(2) && (shifted(1, 0).orR || kept(0)))
    val tooLarge = x.exponent >= 31.S || rounded >= "h80000000".U
    val signed = Mux(x.sign, (~rounded(31, 0)).asUInt + 1.U, rounded(31, 0))
    Mux(x.isNaN, "h7fffffff".U(32.W),
      Mux(x.isInf || tooLarge, Mux(x.sign, "h80000000".U, "h7fffffff".U), signed))
  }
}
