package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import scala.util.Random
import xiangjiang.backend.fu.VectorFloat

class VectorFloatHarness extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(16.W))
    val b = Input(UInt(16.W))
    val c = Input(UInt(16.W))
    val x = Input(UInt(32.W))
    val y = Input(UInt(32.W))
    val integer = Input(UInt(32.W))
    val add = Output(UInt(16.W))
    val sub = Output(UInt(16.W))
    val mul = Output(UInt(16.W))
    val div = Output(UInt(16.W))
    val fma = Output(UInt(16.W))
    val min = Output(UInt(16.W))
    val max = Output(UInt(16.W))
    val equal = Output(Bool())
    val less = Output(Bool())
    val lessEqual = Output(Bool())
    val widen = Output(UInt(32.W))
    val narrow = Output(UInt(16.W))
    val fromInt = Output(UInt(32.W))
    val toInt = Output(UInt(32.W))
    val add32 = Output(UInt(32.W))
    val exp = Output(UInt(16.W))
    val rcp = Output(UInt(16.W))
    val rsqrt = Output(UInt(16.W))
  })
  io.add := VectorFloat.add(io.a, io.b)
  io.sub := VectorFloat.sub(io.a, io.b)
  io.mul := VectorFloat.mul(io.a, io.b)
  io.div := VectorFloat.div(io.a, io.b)
  io.fma := VectorFloat.fma(io.a, io.b, io.c)
  io.min := VectorFloat.min(io.a, io.b)
  io.max := VectorFloat.max(io.a, io.b)
  io.equal := VectorFloat.eq(io.a, io.b)
  io.less := VectorFloat.lt(io.a, io.b)
  io.lessEqual := VectorFloat.le(io.a, io.b)
  io.widen := VectorFloat.widen(io.a)
  io.narrow := VectorFloat.narrow(io.x)
  io.fromInt := VectorFloat.fromInt(io.integer)
  io.toInt := VectorFloat.toInt(io.x)
  io.add32 := VectorFloat.add32(io.x, io.y)
  io.exp := VectorFloat.exp(io.a)
  io.rcp := VectorFloat.rcp(io.a)
  io.rsqrt := VectorFloat.rsqrt(io.a)
}

/** Exact rational oracle: no host floating arithmetic, intermediate rounding,
  * hardware alignment algorithm, or bounded-significand approximation.
  */
class VectorFloatSpec extends AnyFunSuite with ChiselSim {
  private val wordMask = (BigInt(1) << 32) - 1
  private case class Number(sign: Boolean, n: BigInt, d: BigInt, nan: Boolean = false, inf: Boolean = false) {
    def zero: Boolean = !nan && !inf && n == 0
    def signed: BigInt = if (sign) -n else n
  }
  private def decode(bits: BigInt, p: Int): Number = {
    val sign = bits.testBit(p + 7)
    val exp = ((bits >> (p - 1)) & 255).toInt
    val fraction = bits & ((BigInt(1) << (p - 1)) - 1)
    val sig = fraction + (if (exp == 0) BigInt(0) else BigInt(1) << (p - 1))
    val scale = math.max(exp, 1) - 127 - (p - 1)
    Number(sign, if (scale >= 0) sig << scale else sig,
      if (scale >= 0) BigInt(1) else BigInt(1) << -scale,
      nan = exp == 255 && fraction != 0, inf = exp == 255 && fraction == 0)
  }
  private def signBit(sign: Boolean, p: Int): BigInt = if (sign) BigInt(1) << (p + 7) else BigInt(0)
  private def infinity(sign: Boolean, p: Int): BigInt = signBit(sign, p) | (BigInt(255) << (p - 1))
  private def nan(p: Int): BigInt = (BigInt(255) << (p - 1)) | (BigInt(1) << (p - 2))
  private def rne(n: BigInt, d: BigInt): BigInt = {
    val (q, r) = n /% d
    q + (if (r * 2 > d || (r * 2 == d && q.testBit(0))) 1 else 0)
  }
  private def encode(numerator: BigInt, denominator: BigInt, p: Int, negativeZero: Boolean = false): BigInt = {
    val sign = numerator < 0 || (numerator == 0 && negativeZero)
    val n = numerator.abs
    if (n == 0) return signBit(sign, p)
    var exponent = n.bitLength - denominator.bitLength
    val below = if (exponent >= 0) n < (denominator << exponent) else (n << -exponent) < denominator
    if (below) exponent -= 1
    var quantum = math.max(exponent - (p - 1), -126 - (p - 1))
    var q = if (quantum >= 0) rne(n, denominator << quantum) else rne(n << -quantum, denominator)
    if (q >= (BigInt(1) << p)) { q >>= 1; quantum += 1 }
    if (quantum > 127 - (p - 1)) return infinity(sign, p)
    val exp = if (q < (BigInt(1) << (p - 1))) 0 else quantum + (p - 1) + 127
    signBit(sign, p) | (BigInt(exp) << (p - 1)) | (q & ((BigInt(1) << (p - 1)) - 1))
  }
  private def add(a: BigInt, b: BigInt, p: Int): BigInt = {
    val x = decode(a, p); val y = decode(b, p)
    if (x.nan || y.nan || (x.inf && y.inf && x.sign != y.sign)) nan(p)
    else if (x.inf) infinity(x.sign, p)
    else if (y.inf) infinity(y.sign, p)
    else encode(x.signed * y.d + y.signed * x.d, x.d * y.d, p, x.sign && y.sign)
  }
  private def mul(a: BigInt, b: BigInt): BigInt = {
    val x = decode(a, 8); val y = decode(b, 8)
    val sign = x.sign ^ y.sign
    if (x.nan || y.nan || (x.inf && y.zero) || (y.inf && x.zero)) nan(8)
    else if (x.inf || y.inf) infinity(sign, 8)
    else encode(x.signed * y.signed, x.d * y.d, 8, sign)
  }
  private def div(a: BigInt, b: BigInt): BigInt = {
    val x = decode(a, 8); val y = decode(b, 8)
    val sign = x.sign ^ y.sign
    if (x.nan || y.nan || (x.zero && y.zero) || (x.inf && y.inf)) nan(8)
    else if (x.inf || y.zero) infinity(sign, 8)
    else if (x.zero || y.inf) signBit(sign, 8)
    else encode((if (sign) -x.n else x.n) * y.d, x.d * y.n, 8)
  }
  private def fma(a: BigInt, b: BigInt, c: BigInt): BigInt = {
    val x = decode(a, 8); val y = decode(b, 8); val z = decode(c, 8)
    val ps = x.sign ^ y.sign
    val productInf = x.inf || y.inf
    if (x.nan || y.nan || z.nan || (x.inf && y.zero) || (y.inf && x.zero) ||
      (productInf && z.inf && ps != z.sign)) nan(8)
    else if (productInf) infinity(ps, 8)
    else if (z.inf) infinity(z.sign, 8)
    else encode(x.signed * y.signed * z.d + z.signed * x.d * y.d,
      x.d * y.d * z.d, 8, ps && z.sign)
  }
  private def compare(a: BigInt, b: BigInt): Option[Int] = {
    val x = decode(a, 8); val y = decode(b, 8)
    if (x.nan || y.nan) None
    else if (x.zero && y.zero) Some(0)
    else if (x.sign != y.sign) Some(if (x.sign) -1 else 1)
    else {
      val magnitude = (a & 0x7fff).compare(b & 0x7fff)
      Some(if (x.sign) -magnitude else magnitude)
    }
  }
  private def minMax(a: BigInt, b: BigInt, maximum: Boolean): BigInt = {
    val x = decode(a, 8); val y = decode(b, 8)
    if (x.nan && y.nan) nan(8)
    else if (x.nan) b
    else if (y.nan) a
    else if (x.zero && y.zero) signBit(if (maximum) x.sign && y.sign else x.sign || y.sign, 8)
    else if (compare(a, b).get < 0) { if (maximum) b else a }
    else { if (maximum) a else b }
  }
  private def narrow(a: BigInt): BigInt = {
    val x = decode(a, 24)
    if (x.nan) nan(8) else if (x.inf) infinity(x.sign, 8) else encode(x.signed, x.d, 8, x.sign)
  }
  private def toInt(a: BigInt): BigInt = {
    val x = decode(a, 24)
    if (x.nan) BigInt("7fffffff", 16)
    else if (x.inf) { if (x.sign) BigInt("80000000", 16) else BigInt("7fffffff", 16) }
    else {
      val magnitude = rne(x.n, x.d)
      val signed = if (x.sign) -magnitude else magnitude
      signed.max(-(BigInt(1) << 31)).min((BigInt(1) << 31) - 1) & wordMask
    }
  }

  test("BF16 arithmetic and conversions match exact rationals at boundaries and random bit patterns") {
    simulate(new VectorFloatHarness) { dut =>
      def check(a: BigInt, b: BigInt, c: BigInt, x: BigInt, y: BigInt, integer: BigInt): Unit = {
        dut.io.a.poke(a); dut.io.b.poke(b); dut.io.c.poke(c)
        dut.io.x.poke(x); dut.io.y.poke(y); dut.io.integer.poke(integer)
        withClue(s"a=${a.toString(16)} b=${b.toString(16)} c=${c.toString(16)} x=${x.toString(16)} y=${y.toString(16)} int=${integer.toString(16)}: ") {
          dut.io.add.expect(add(a, b, 8))
          dut.io.sub.expect(add(a, b ^ 0x8000, 8))
          dut.io.mul.expect(mul(a, b))
          dut.io.div.expect(div(a, b))
          dut.io.fma.expect(fma(a, b, c))
          dut.io.min.expect(minMax(a, b, maximum = false))
          dut.io.max.expect(minMax(a, b, maximum = true))
          val cmp = compare(a, b)
          dut.io.equal.expect(cmp.contains(0))
          dut.io.less.expect(cmp.exists(_ < 0))
          dut.io.lessEqual.expect(cmp.exists(_ <= 0))
          dut.io.widen.expect(if (decode(a, 8).nan) nan(24) else a << 16)
          dut.io.narrow.expect(narrow(x))
          val signedInt = if (integer.testBit(31)) integer - (BigInt(1) << 32) else integer
          dut.io.fromInt.expect(encode(signedInt, 1, 24))
          dut.io.toInt.expect(toInt(x))
          dut.io.add32.expect(add(x, y, 24))
        }
      }
      val edges = Seq(0x0000, 0x8000, 0x0001, 0x8001, 0x0002, 0x007f, 0x807f,
        0x0080, 0x8080, 0x0081, 0x3f00, 0x3f80, 0xbf80, 0x3f81, 0x3f82,
        0x3fc0, 0x4000, 0xc000, 0x7f7f, 0xff7f, 0x7f80, 0xff80,
        0x7fc0, 0xffc1, 0x7f81).map(BigInt(_))
      val floatEdges = Seq("00000000", "80000000", "00000001", "80000001",
        "00008000", "00008001", "00018000", "007f8000", "007fffff", "00800000",
        "3f000000", "bf000000", "3fc00000", "bfc00000", "40200000", "c0200000",
        "3f808000", "3f818000", "4affffff", "4b000001", "4effffff", "4f000000",
        "cf000000", "cf000001", "7f7f7fff", "7f7f8000", "7f7fffff", "7f800000",
        "ff800000", "7fc00000", "7f800001").map(BigInt(_, 16))
      val integerEdges = Seq(0L, 1L, -1L, 16777215L, 16777216L, 16777217L,
        16777219L, -16777217L, -16777219L, 2147483647L, -2147483648L).map(x => BigInt(x) & wordMask)
      for ((a, i) <- edges.zipWithIndex; (b, j) <- edges.zipWithIndex) {
        check(a, b, edges((i * 7 + j * 3) % edges.size),
          floatEdges((i + j) % floatEdges.size), floatEdges((i * 3 + j) % floatEdges.size),
          integerEdges((i + j) % integerEdges.size))
      }
      // Exact product cancellation: multiply-then-add would incorrectly produce zero.
      assert(fma(0x3f81, 0x3f81, 0xbf82) == 0x3880)
      assert(add(mul(0x3f81, 0x3f81), 0xbf82, 8) == 0)
      check(0x3f81, 0x3f81, 0xbf82, 0x3f800000, 0xbf800000L, 0)
      // Even an FP32 intermediate loses this tiny perturbation below a BF16 tie.
      assert(fma(0x3f81, 0x3fc0, 0xb080) == 0x3fc1)
      check(0x3f81, 0x3fc0, 0xb080, 0x3f800000, 0x33800000, 16777217)
      // Add/sub ties, normal/subnormal cancellation, and division's exact/tie cases.
      val directed = Seq((0x3f80, 0x3b80, 0), (0x3f81, 0x3b80, 0),
        (0x0080, 0x807f, 0), (0x0001, 0x4000, 0), (0x0003, 0x4000, 0),
        (0x7f7f, 0x3f80, 0xff7f), (0x0080, 0x3f00, 0x8001),
        (0x8000, 0x3f80, 0x8000), (0x0000, 0xbf80, 0x0000))
      directed.foreach { case (a, b, c) => check(a, b, c, 0, 0, 0) }
      // Every directed FP32 boundary also participates in exact FP32 addition.
      for (x <- floatEdges; y <- floatEdges) check(0, 0, 0, x, y, integerEdges((x % integerEdges.size).toInt))
      val random = new Random(0x51a7bf16L)
      for (_ <- 0 until 1500) {
        check(BigInt(random.nextInt(65536)), BigInt(random.nextInt(65536)), BigInt(random.nextInt(65536)),
          BigInt(random.nextInt()) & wordMask, BigInt(random.nextInt()) & wordMask, BigInt(random.nextInt()) & wordMask)
      }
      // Dense finite/cancellation cases complement uniformly random encodings.
      for (_ <- 0 until 500) {
        val a = BigInt(0x3f00 | random.nextInt(256))
        val b = BigInt(0x3f00 | random.nextInt(256))
        val c = mul(a, b) ^ 0x8000
        check(a, b, c, (a << 16) | random.nextInt(65536), (b << 16) ^ BigInt("80000000", 16),
          BigInt(random.nextInt()) & wordMask)
      }
    }
  }

  test("special functions handle exact values and IEEE special operands") {
    simulate(new VectorFloatHarness) { dut =>
      def check(a: BigInt, exp: BigInt, rcp: BigInt, rsqrt: BigInt): Unit = {
        dut.io.a.poke(a)
        withClue(s"a=${a.toString(16)}: ") {
          dut.io.exp.expect(exp)
          dut.io.rcp.expect(rcp)
          dut.io.rsqrt.expect(rsqrt)
        }
      }
      val inf = BigInt(0x7f80)
      val ninf = BigInt(0xff80)
      val qnan = BigInt(0x7fc0)
      check(0x0000, 0x3f80, inf, inf)
      check(0x8000, 0x3f80, ninf, ninf)
      check(0x3f80, 0x402e, 0x3f80, 0x3f80) // exp(1) ~= e, 1/1, 1/sqrt(1)
      check(0xbf80, 0x3ebc, 0xbf80, qnan) // exp(-1), -1, sqrt(-1)
      check(0x4000, 0x40ec, 0x3f00, 0x3f35) // exp(2), 1/2, 1/sqrt(2)
      check(0x4080, 0x425a, 0x3e80, 0x3f00) // exp(4), 1/4, 1/sqrt(4)
      check(inf, inf, 0x0000, 0x0000)
      check(ninf, 0x0000, 0x8000, qnan)
      check(qnan, qnan, qnan, qnan)
    }
  }
}
