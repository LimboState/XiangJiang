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

  /** Round the exact/sticky magnitude times 2^scale to a p-bit significand. */
  private def round(magnitude: UInt, scale: SInt, sign: Bool, p: Int): UInt = {
    val w = magnitude.getWidth
    require(w >= p + 3)
    val leading = PriorityEncoder(Reverse(magnitude))
    val normalized = (magnitude << leading)(w - 1, 0)
    val exponent = scale + (w - 1).S(12.W) - leading.zext
    val underflow = Mux(exponent < emin.S, emin.S(12.W) - exponent, 0.S(12.W))
    val shifted = jam(normalized, ((w - p - 3).S(12.W) + underflow).asUInt)
    val kept = shifted(p + 2, 3)
    val increment = shifted(2) && (shifted(1, 0).orR || kept(0))
    val rounded = kept +& increment
    val effectiveExponent = Mux(exponent < emin.S, emin.S(12.W), exponent)
    val finalExponent = effectiveExponent + rounded(p).asUInt.zext
    val fraction = Mux(rounded(p), rounded(p - 1, 1), rounded(p - 2, 0))
    val biased = (finalExponent + bias.S(12.W)).asUInt
    val expField = Mux(rounded(p) || rounded(p - 1), biased(7, 0), 0.U(8.W))
    Mux(!magnitude.orR, zero(sign, p),
      Mux(finalExponent > 127.S, infinity(sign, p), Cat(sign, expField, fraction)))
  }

  /** Inputs have their leading bit at w-2 and an unbiased exponent. */
  private def sumMagnitudes(a: UInt, ae: SInt, as: Bool,
                            b: UInt, be: SInt, bs: Bool, p: Int): UInt = {
    val w = a.getWidth
    val common = Mux(ae >= be, ae, be)
    val ax = jam(a, (common - ae).asUInt)
    val bx = jam(b, (common - be).asUInt)
    val aLarger = ax >= bx
    val magnitude = Mux(as === bs, ax + bx, Mux(aLarger, ax - bx, bx - ax))
    val sign = Mux(!magnitude.orR, as && bs, Mux(as === bs || aLarger, as, bs))
    round(magnitude, common - (w - 2).S(12.W), sign, p)
  }

  private def addGeneric(a: UInt, b: UInt, p: Int): UInt = {
    val x = new Parts(a, p)
    val y = new Parts(b, p)
    val w = p + 5
    val ax = Cat(0.U(1.W), x.normalized, 0.U((w - p - 1).W))
    val bx = Cat(0.U(1.W), y.normalized, 0.U((w - p - 1).W))
    // Zero's arbitrary decoded exponent must not shift a tiny nonzero operand.
    val ae = Mux(x.isZero, (-512).S(12.W), x.exponent)
    val be = Mux(y.isZero, (-512).S(12.W), y.exponent)
    val finite = sumMagnitudes(ax, ae, x.sign, bx, be, y.sign, p)
    Mux(x.isNaN || y.isNaN || (x.isInf && y.isInf && x.sign =/= y.sign), nan(p),
      Mux(x.isInf, infinity(x.sign, p), Mux(y.isInf, infinity(y.sign, p), finite)))
  }

  def add(a: UInt, b: UInt): UInt = addGeneric(a(15, 0), b(15, 0), 8)
  def sub(a: UInt, b: UInt): UInt = add(a, Cat(!b(15), b(14, 0)))
  def add32(a: UInt, b: UInt): UInt = addGeneric(a(31, 0), b(31, 0), 24)

  private def mulGeneric(a: UInt, b: UInt, p: Int): UInt = {
    val x = new Parts(a, p)
    val y = new Parts(b, p)
    val sign = x.sign ^ y.sign
    val product = x.normalized * y.normalized
    val finite = round(Cat(product, 0.U(3.W)),
      x.exponent + y.exponent - (2 * p + 1).S(12.W), sign, p)
    Mux(x.isNaN || y.isNaN || (x.isInf && y.isZero) || (y.isInf && x.isZero), nan(p),
      Mux(x.isInf || y.isInf, infinity(sign, p), finite))
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

  def div(a: UInt, b: UInt): UInt = divGeneric(a(15, 0), b(15, 0), 8)

  def fma(a: UInt, b: UInt, c: UInt): UInt = {
    val x = new Parts(a, 8)
    val y = new Parts(b, 8)
    val z = new Parts(c, 8)
    val ps = x.sign ^ y.sign
    val product = x.normalized * y.normalized
    val high = product(15)
    val pm = Mux(high, Cat(0.U(1.W), product, 0.U(4.W)),
      Cat(0.U(1.W), product(14, 0), 0.U(5.W)))
    val cm = Cat(0.U(1.W), z.normalized, 0.U(12.W))
    val pe = Mux(x.isZero || y.isZero, (-512).S(12.W),
      x.exponent + y.exponent + high.asUInt.zext)
    val ce = Mux(z.isZero, (-512).S(12.W), z.exponent)
    val finite = sumMagnitudes(pm, pe, ps, cm, ce, z.sign, 8)
    val invalidProduct = (x.isInf && y.isZero) || (y.isInf && x.isZero)
    val productInf = x.isInf || y.isInf
    Mux(x.isNaN || y.isNaN || z.isNaN || invalidProduct ||
      (productInf && z.isInf && ps =/= z.sign), nan(8),
      Mux(productInf, infinity(ps, 8), Mux(z.isInf, infinity(z.sign, 8), finite)))
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

  def widen(a: UInt): UInt = {
    val x = new Parts(a, 8)
    Mux(x.isNaN, nan(24), Cat(a(15, 0), 0.U(16.W)))
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

  // ---------------- FP32 with rounding modes and exception flags ----------
  // Flags layout: bit4 NV, bit3 DZ, bit2 OF, bit1 UF, bit0 NX.  Each function
  // returns a (result, flags) pair.
  private def roundModeInc(kept: UInt, shifted: UInt, sign: Bool, rm: UInt): Bool = {
    val inexact = shifted(2, 0).orR
    // rm must be at least 3 bits: Chisel 7.16's MuxLookup assumes a mapping
    // covers every key value when the masked literal keys fill the key width,
    // which silently replaces the RNE default for 1-bit rm literals.
    val rm3 = rm.pad(3)
    MuxLookup(rm3, shifted(2) && (shifted(1, 0).orR || kept(0)))(Seq(
      1.U -> false.B, // RTZ
      2.U -> (sign && inexact), // RDN
      3.U -> (!sign && inexact), // RUP
      4.U -> shifted(2))) // RMM ties away
  }

  private def roundF(magnitude: UInt, scale: SInt, sign: Bool, p: Int, rm: UInt): (UInt, UInt) = {
    val w = magnitude.getWidth
    require(w >= p + 3)
    val leading = PriorityEncoder(Reverse(magnitude))
    val normalized = (magnitude << leading)(w - 1, 0)
    val exponent = scale + (w - 1).S(12.W) - leading.zext
    val underflow = Mux(exponent < emin.S, emin.S(12.W) - exponent, 0.S(12.W))
    val shifted = jam(normalized, ((w - p - 3).S(12.W) + underflow).asUInt)
    val kept = shifted(p + 2, 3)
    val increment = roundModeInc(kept, shifted, sign, rm)
    val rounded = kept +& increment
    val effectiveExponent = Mux(exponent < emin.S, emin.S(12.W), exponent)
    val finalExponent = effectiveExponent + rounded(p).asUInt.zext
    val fraction = Mux(rounded(p), rounded(p - 1, 1), rounded(p - 2, 0))
    val biased = (finalExponent + bias.S(12.W)).asUInt
    val expField = Mux(rounded(p) || rounded(p - 1), biased(7, 0), 0.U(8.W))
    val inexact = shifted(2, 0).orR
    val overflow = finalExponent > 127.S
    val tiny = magnitude.orR &&
      (finalExponent < emin.S || (!rounded(p) && !rounded(p - 1)))
    val result = Mux(!magnitude.orR, zero(sign, p),
      Mux(overflow, infinity(sign, p), Cat(sign, expField, fraction)))
    (result, Cat(0.U(1.W), 0.U(1.W), overflow, tiny && inexact, inexact || overflow))
  }

  private def snan(x: Parts): Bool = x.isNaN && !x.fraction(22)

  def f32Add(a: UInt, b: UInt, rm: UInt): (UInt, UInt) = {
    val x = new Parts(a(31, 0), 24)
    val y = new Parts(b(31, 0), 24)
    val w = 29
    val ax = Cat(0.U(1.W), x.normalized, 0.U(4.W))
    val bx = Cat(0.U(1.W), y.normalized, 0.U(4.W))
    val ae = Mux(x.isZero, (-512).S(12.W), x.exponent)
    val be = Mux(y.isZero, (-512).S(12.W), y.exponent)
    val common = Mux(ae >= be, ae, be)
    val axs = jam(ax, (common - ae).asUInt)
    val bxs = jam(bx, (common - be).asUInt)
    val aLarger = axs >= bxs
    val magnitude = Mux(x.sign === y.sign, axs + bxs, Mux(aLarger, axs - bxs, bxs - axs))
    val sign = Mux(!magnitude.orR, x.sign && y.sign,
      Mux(x.sign === y.sign || aLarger, x.sign, y.sign))
    val rounded = roundF(magnitude, common - 27.S(12.W), sign, 24, rm)
    val nv = snan(x) || snan(y) || (x.isInf && y.isInf && x.sign =/= y.sign)
    val result = Mux(x.isNaN || y.isNaN, nan(24),
      Mux(x.isInf && y.isInf && x.sign =/= y.sign, nan(24),
        Mux(x.isInf || y.isInf, infinity(Mux(x.isInf, x.sign, y.sign), 24), rounded._1)))
    (result, rounded._2 | Cat(nv, 0.U(4.W)))
  }

  def f32Mul(a: UInt, b: UInt, rm: UInt): (UInt, UInt) = {
    val x = new Parts(a(31, 0), 24)
    val y = new Parts(b(31, 0), 24)
    val sign = x.sign ^ y.sign
    val product = x.normalized * y.normalized
    val finite = roundF(Cat(product, 0.U(3.W)),
      x.exponent + y.exponent - 49.S(12.W), sign, 24, rm)
    val nv = snan(x) || snan(y) || (x.isInf && y.isZero) || (y.isInf && x.isZero)
    val result = Mux(x.isNaN || y.isNaN, nan(24),
      Mux(x.isInf || y.isInf, infinity(sign, 24), finite._1))
    (result, finite._2 | Cat(nv, 0.U(4.W)))
  }

  def f32Div(a: UInt, b: UInt, rm: UInt): (UInt, UInt) = {
    val x = new Parts(a(31, 0), 24)
    val y = new Parts(b(31, 0), 24)
    val sign = x.sign ^ y.sign
    val fractional = 28
    val numerator = Cat(x.normalized, 0.U(fractional.W))
    val denominator = Mux(y.isZero, 1.U(24.W), y.normalized)
    val quotient = (numerator / denominator)(fractional, 0)
    val inexact = numerator =/= quotient * denominator
    val finite = roundF(quotient | inexact.asUInt,
      x.exponent - y.exponent - fractional.S(12.W), sign, 24, rm)
    val nv = snan(x) || snan(y) || (x.isZero && y.isZero) || (x.isInf && y.isInf)
    val dz = !x.isInf && !x.isNaN && y.isZero && !x.isZero
    val result = Mux(x.isNaN || y.isNaN || (x.isZero && y.isZero) || (x.isInf && y.isInf), nan(24),
      Mux(x.isInf || y.isZero, infinity(sign, 24),
        Mux(x.isZero || y.isInf, zero(sign, 24), finite._1)))
    (result, finite._2 | Cat(nv, dz, 0.U(3.W)))
  }

  def f32Sqrt(a: UInt, rm: UInt): (UInt, UInt) = {
    val x = new Parts(a(31, 0), 24)
    // Even exponent: x = 1.f x 2^E with sqrt = sqrt(1.f) x 2^(E/2).
    // Odd exponent: x = (2 x 1.f) x 2^(E-1).  In both cases sqrt(mm) with
    // mm = (sig << shift) << 23 lands on integer bits in [2^23, 2^24).
    val shift = x.exponent(0)
    val mInt = Mux(shift, Cat(x.normalized, 0.U(1.W)), x.normalized) // 25 bits
    val mm = Cat(mInt, 0.U(23.W)) // 48 bits
    var r = 0.U(24.W)
    for (i <- 23 to 0 by -1) {
      val cand = r | (BigInt(1) << i).U(24.W)
      r = Mux((cand * cand) <= mm, cand, r)
    }
    val rem = mm - (r * r)
    val inexact = rem.orR
    val twoRPlusOne = Cat(r, 0.U(1.W)) + 1.U
    val tie = (twoRPlusOne * twoRPlusOne) === (mm << 2)
    val halfUp = rem > r
    val inc = MuxLookup(rm, halfUp || (tie && r(0)))(Seq(
      1.U -> false.B, // RTZ
      2.U -> false.B, // RDN, sign positive: floor
      3.U -> inexact, // RUP
      4.U -> (halfUp || tie))) // RMM ties away
    val rounded = r +& inc
    val carry = rounded(24)
    val resultExp = (x.exponent >> 1) + carry.asUInt.zext
    val biased = resultExp + bias.S(12.W)
    val fraction = Mux(carry, 0.U(23.W), rounded(22, 0))
    val nv = snan(x) || (x.sign && !x.isZero)
    val result = Mux(x.isNaN, nan(24),
      Mux(x.isInf, infinity(x.sign, 24),
        Mux(x.isZero, zero(x.sign, 24),
          Mux(x.sign, nan(24), Cat(0.U(1.W), biased(7, 0), fraction)))))
    (result, Cat(nv, 0.U(1.W), 0.U(1.W), 0.U(1.W), inexact))
  }

  def f32Fma(a: UInt, b: UInt, c: UInt, rm: UInt): (UInt, UInt) = {
    val x = new Parts(a(31, 0), 24)
    val y = new Parts(b(31, 0), 24)
    val z = new Parts(c(31, 0), 24)
    val ps = x.sign ^ y.sign
    val product = x.normalized * y.normalized
    val high = product(47)
    val pm = Mux(high, Cat(0.U(1.W), product, 0.U(4.W)),
      Cat(0.U(1.W), product(46, 0), 0.U(5.W)))
    val cm = Cat(0.U(1.W), z.normalized, 0.U(28.W))
    val pe = Mux(x.isZero || y.isZero, (-512).S(12.W),
      x.exponent + y.exponent + high.asUInt.zext)
    val ce = Mux(z.isZero, (-512).S(12.W), z.exponent)
    val common = Mux(pe >= ce, pe, ce)
    val axs = jam(pm, (common - pe).asUInt)
    val bxs = jam(cm, (common - ce).asUInt)
    val aLarger = axs >= bxs
    val magnitude = Mux(ps === z.sign, axs + bxs, Mux(aLarger, axs - bxs, bxs - axs))
    val sign = Mux(!magnitude.orR, ps && z.sign, Mux(ps === z.sign || aLarger, ps, z.sign))
    val rounded = roundF(magnitude, common - 51.S(12.W), sign, 24, rm)
    val invalidProduct = (x.isInf && y.isZero) || (y.isInf && x.isZero)
    val productInf = x.isInf || y.isInf
    val nv = snan(x) || snan(y) || snan(z) || invalidProduct ||
      (productInf && z.isInf && ps =/= z.sign)
    val result = Mux(x.isNaN || y.isNaN || z.isNaN || invalidProduct, nan(24),
      Mux(productInf, infinity(ps, 24), Mux(z.isInf, infinity(z.sign, 24), rounded._1)))
    (result, rounded._2 | Cat(nv, 0.U(4.W)))
  }

  def f32Cmp(a: UInt, b: UInt, op: UInt): (Bool, Bool) = {
    val x = new Parts(a(31, 0), 24)
    val y = new Parts(b(31, 0), 24)
    val eq = !x.isNaN && !y.isNaN && (a(31, 0) === b(31, 0) || (x.isZero && y.isZero))
    val lt = !x.isNaN && !y.isNaN && !(x.isZero && y.isZero) &&
      Mux(x.sign =/= y.sign, x.sign, Mux(x.sign, a(30, 0) > b(30, 0), a(30, 0) < b(30, 0)))
    val nv = snan(x) || snan(y)
    (MuxLookup(op, eq)(Seq(0.U -> eq, 1.U -> lt, 2.U -> (lt || eq))), nv)
  }

  def f32ToInt(a: UInt, rm: UInt, unsigned: Boolean): (UInt, UInt) = {
    val unsignedB = unsigned.B
    val x = new Parts(a(31, 0), 24)
    val source = Cat(0.U(8.W), x.normalized, 0.U(3.W))
    val right = 23.S(12.W) - x.exponent
    val left = Mux(right < 0.S, -right, 0.S(12.W)).asUInt
    val leftBounded = Mux(left > 34.U, 34.U, left)(5, 0)
    val shifted = Mux(right >= 0.S, jam(source, right.asUInt), (source << leftBounded)(34, 0))
    val kept = shifted(34, 3)
    val increment = roundModeInc(kept, shifted, x.sign, rm)
    val rounded = kept +& increment
    val inexact = shifted(2, 0).orR
    val negative = x.sign && !x.isZero
    val tooLarge = x.exponent >= 31.S || rounded >= "h80000000".U
    val signed = Mux(x.sign, (~rounded(31, 0)).asUInt + 1.U, rounded(31, 0))
    val unsignedResult = Mux(negative, 0.U(32.W),
      Mux(x.isInf && x.sign, 0.U(32.W),
        Mux(x.isNaN || (x.isInf && !x.sign) || tooLarge || x.exponent >= 32.S, "hffffffff".U,
          rounded(31, 0))))
    val signedResult = Mux(x.isNaN, "h7fffffff".U(32.W),
      Mux(x.isInf || tooLarge, Mux(x.sign, "h80000000".U, "h7fffffff".U), signed))
    val nv = snan(x) || x.isInf || tooLarge || x.exponent >= 32.S || (unsignedB && negative)
    (Mux(unsignedB, unsignedResult, signedResult), Cat(nv, 0.U(1.W), 0.U(1.W), 0.U(1.W), inexact))
  }

  def intToF32(a: UInt, rm: UInt, unsigned: Boolean): (UInt, UInt) = {
    val unsignedB = unsigned.B
    val sign = !unsignedB && a(31)
    val magnitude = Mux(unsignedB || !sign, a(31, 0), (~a(31, 0)).asUInt + 1.U)
    val r = roundF(Cat(magnitude, 0.U(3.W)), (-3).S(12.W), sign, 24, rm)
    (r._1, r._2 & "b10001".U(5.W))
  }

  // ---------------- FP32 vector operations (fixed RNE, no flags) ----------
  def div32(a: UInt, b: UInt): UInt = {
    val x = new Parts(a(31, 0), 24)
    val y = new Parts(b(31, 0), 24)
    val sign = x.sign ^ y.sign
    val fractional = 28
    val numerator = Cat(x.normalized, 0.U(fractional.W))
    val denominator = Mux(y.isZero, 1.U(24.W), y.normalized)
    val quotient = (numerator / denominator)(fractional, 0)
    val inexact = numerator =/= quotient * denominator
    val finiteV = round(quotient | inexact.asUInt,
      x.exponent - y.exponent - fractional.S(12.W), sign, 24)
    Mux(x.isNaN || y.isNaN || (x.isZero && y.isZero) || (x.isInf && y.isInf), nan(24),
      Mux(x.isInf || y.isZero, infinity(sign, 24),
        Mux(x.isZero || y.isInf, zero(sign, 24), finiteV)))
  }

  def sqrt32(a: UInt): UInt = f32Sqrt(a(31, 0), 0.U)._1
  def fma32(a: UInt, b: UInt, c: UInt): UInt = f32Fma(a(31, 0), b(31, 0), c(31, 0), 0.U)._1
  def eq32(a: UInt, b: UInt): Bool = f32Cmp(a(31, 0), b(31, 0), 0.U)._1
  def lt32(a: UInt, b: UInt): Bool = f32Cmp(a(31, 0), b(31, 0), 1.U)._1
  def le32(a: UInt, b: UInt): Bool = f32Cmp(a(31, 0), b(31, 0), 2.U)._1
  def max32(a: UInt, b: UInt): UInt = {
    val x = new Parts(a(31, 0), 24)
    val y = new Parts(b(31, 0), 24)
    val ordered = Mux(lt32(a, b), b(31, 0), a(31, 0))
    val zeroSign = x.sign && y.sign
    Mux(x.isNaN && y.isNaN, nan(24), Mux(x.isNaN, b(31, 0), Mux(y.isNaN, a(31, 0),
      Mux(x.isZero && y.isZero, zero(zeroSign, 24), ordered))))
  }
  def min32(a: UInt, b: UInt): UInt = {
    val x = new Parts(a(31, 0), 24)
    val y = new Parts(b(31, 0), 24)
    val ordered = Mux(lt32(a, b), a(31, 0), b(31, 0))
    val zeroSign = x.sign || y.sign
    Mux(x.isNaN && y.isNaN, nan(24), Mux(x.isNaN, b(31, 0), Mux(y.isNaN, a(31, 0),
      Mux(x.isZero && y.isZero, zero(zeroSign, 24), ordered))))
  }
  def toInt32(a: UInt): UInt = f32ToInt(a(31, 0), 0.U, false)._1
  def toUInt32(a: UInt): UInt = f32ToInt(a(31, 0), 0.U, true)._1
  def fromInt32(a: UInt): UInt = intToF32(a(31, 0), 0.U, false)._1
  def fromUInt32(a: UInt): UInt = intToF32(a(31, 0), 0.U, true)._1
}
