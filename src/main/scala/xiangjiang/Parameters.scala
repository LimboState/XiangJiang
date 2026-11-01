package xiangjiang

import chisel3.util._

/** XJ-NPU V1 baseline parameters (docs/XJ-NPU.md section 1.2).
  *
  * The RISC-V and vector register widths are architectural constants. The
  * DTCM beat is an independent 512-bit transport parameter and must not be
  * confused with the 128-bit vector width. Matrix arrays are 3 x 8x8. The
  * instruction fetch/data paths are 32-bit. DMA keeps one active command and
  * one outstanding AXI burst with a 64-byte buffer; a single read burst is
  * limited to at most 64 bytes of payload.
  */
case class XJParams(
  // Implementation parameters (queue depths, sizes, latency choices).
  nRob: Int = 8,
  nIssue: Int = 4,
  nXRes: Int = 6,
  nMemq: Int = 4,
  generationBits: Int = 4,
  seqBits: Int = 16,
  bankBytes: Int = 256 * 1024,
  axiIdBits: Int = 4,
  nVRes: Int = 4,
  nFRes: Int = 4,
  dmaMaxBurstBeats: Int = 16,
  // Architectural constants (baseline values from the architecture document).
  val xlen: Int = 32,
  val flen: Int = 32,
  val vlen: Int = 128,
  val dlen: Int = 128,
  val elen: Int = 32,
  val nDtcmBanks: Int = 4,
  val dtcmBeatBytes: Int = 64,
  val dtcmBeatBits: Int = 512,
  val nMatrixUnits: Int = 3,
  val matrixRows: Int = 8,
  val matrixCols: Int = 8
) {
  require(nRob >= 1)
  require(nIssue >= 1 && nXRes >= 1 && nVRes >= 1 && nFRes >= 1 && nMemq >= 1)
  require(generationBits >= 1 && seqBits >= log2Ceil(nRob) + 2)
  require(bankBytes >= 64 && bankBytes % 64 == 0)
  require(BigInt(bankBytes) * nDtcmBanks <= (BigInt(1) << 20),
    "physical DTCM (bankBytes x nDtcmBanks) must fit the 1 MiB aperture")
  require(axiIdBits >= 1)
  require(dmaMaxBurstBeats >= 1 && dmaMaxBurstBeats <= 16,
    "one read burst carries at most one 64-byte buffer (16 x 32-bit beats)")
  require(xlen == 32, "XJ-NPU V1 baseline fixes XLEN=32")
  require(flen == 32, "XJ-NPU V1 baseline fixes FLEN=32")
  require(vlen == 128 && dlen == 128 && elen == 32, "XJ-NPU V1 baseline fixes VLEN=DLEN=128, ELEN=32")
  require(vlen % dlen == 0)
  require(nDtcmBanks == 4, "XJ-NPU V1 baseline fixes four DTCM banks")
  require(dtcmBeatBytes == 64 && dtcmBeatBits == 512, "XJ-NPU V1 baseline fixes the 64-byte DTCM beat")
  require(nMatrixUnits == 3 && matrixRows == 8 && matrixCols == 8,
    "XJ-NPU V1 baseline fixes three 8x8 matrix arrays")
  val robBits = log2Ceil(nRob)
  val itcmBytes = 256 * 1024
  val dtcmBytes = bankBytes * nDtcmBanks
}
