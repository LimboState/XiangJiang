package xiangjiang

import chisel3.util._

case class XJParams(
  nRob: Int = 8,
  nIssue: Int = 4,
  nXRes: Int = 6,
  nMemq: Int = 4,
  generationBits: Int = 4,
  seqBits: Int = 16,
  bankBytes: Int = 4096,
  axiIdBits: Int = 4,
  nVRes: Int = 4
) {
  require(nRob >= 1)
  require(nIssue >= 1 && nXRes >= 1 && nVRes >= 1 && nMemq >= 1)
  require(generationBits >= 1 && seqBits >= log2Ceil(nRob) + 2)
  require(bankBytes >= 64 && bankBytes % 64 == 0)
  require(BigInt(bankBytes) * 4 <= BigInt("10000000", 16))
  require(axiIdBits >= 1)
  val robBits = log2Ceil(nRob)
  val itcmBytes = 512 * 1024
  val dtcmBytes = bankBytes * 4
}

