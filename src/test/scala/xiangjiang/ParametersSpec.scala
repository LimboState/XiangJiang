package xiangjiang

import org.scalatest.funsuite.AnyFunSuite

/** Baseline parameter validation: architectural constants are fixed and
  * illegal implementation parameters fail at elaboration time.
  */
class ParametersSpec extends AnyFunSuite {
  test("baseline parameters match docs/XJ-NPU.md section 1.2") {
    val p = XJParams()
    assert(p.xlen == 32 && p.flen == 32)
    assert(p.vlen == 128 && p.dlen == 128 && p.elen == 32)
    assert(p.itcmBytes == 256 * 1024)
    assert(p.dtcmBytes == 1024 * 1024)
    assert(p.bankBytes == 256 * 1024)
    assert(p.nDtcmBanks == 4)
    assert(p.dtcmBeatBytes == 64 && p.dtcmBeatBits == 512)
    assert(p.nMatrixUnits == 3 && p.matrixRows == 8 && p.matrixCols == 8)
    assert(p.dmaMaxBurstBeats == 16)
  }

  test("physical DTCM must fit the 1 MiB aperture and bank geometry must be legal") {
    intercept[IllegalArgumentException] { XJParams(bankBytes = 1024 * 1024) }
    intercept[IllegalArgumentException] { XJParams(bankBytes = 100) }
    intercept[IllegalArgumentException] { XJParams(nRob = 0) }
    intercept[IllegalArgumentException] { XJParams(generationBits = 0) }
    intercept[IllegalArgumentException] { XJParams(dmaMaxBurstBeats = 32) }
    intercept[IllegalArgumentException] { XJParams(vlen = 256) }
    intercept[IllegalArgumentException] { XJParams(matrixRows = 16) }
  }

  test("small simulation geometries scale down but keep the 64-byte beat mapping") {
    val p = XJParams(bankBytes = 1024)
    assert(p.dtcmBytes == 4096)
    assert(p.bankBytes % 64 == 0)
    assert(p.robBits == 3)
  }
}
