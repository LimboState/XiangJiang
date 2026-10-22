package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.decode.DecodeUnit

class MatrixDecodeSpec extends AnyFunSuite with ChiselSim {
  test("matrix encodings decode to the expected tasks and barriers") {
    simulate(new DecodeUnit(XJParams())) { c =>
      c.io.in.pc.poke(0x100)
      c.io.in.exception.poke(false)
      c.io.in.cause.poke(0)
      c.io.in.badAddr.poke(0)
      import MatrixEncoding._
      def check(inst: BigInt, op: MatOp.Type, fu: FuType.Type, rs1: Boolean,
                barrier: Boolean, unit: Int = 0, buffer: Int = 0,
                checkUnit: Boolean = true): Unit = {
        c.io.in.inst.poke(inst)
        c.io.out.exception.expect(false)
        c.io.out.op.expect(UOpCode.MATRIX)
        c.io.out.fu.expect(fu)
        c.io.out.matOp.expect(op)
        if (checkUnit) {
          c.io.out.matUnit.expect(unit)
          c.io.out.matBuffer.expect(buffer)
        }
        c.io.out.usesRs1.expect(rs1)
        c.io.out.usesRs2.expect(false)
        c.io.out.writesRd.expect(false)
        c.io.out.barrier.expect(barrier)
      }
      check(msetmode(0, 0), MatOp.msetmode, FuType.system, false, true)
      check(msetmode(1, 1), MatOp.msetmode, FuType.system, false, true, unit = 1)
      check(mload(2, 0, 7), MatOp.mload, FuType.mem, true, false, unit = 2, buffer = 0)
      check(mload(2, 2, 8), MatOp.mload, FuType.mem, true, false, unit = 2, buffer = 2)
      check(mstore(1, 9), MatOp.mstore, FuType.mem, true, false, unit = 1)
      check(mmul(1), MatOp.mmul, FuType.matrix, false, false, unit = 1)
      check(mmacc(2), MatOp.mmacc, FuType.matrix, false, false, unit = 2)
      check(mew(0), MatOp.mew, FuType.matrix, false, false)
      check(mtrans(0), MatOp.mtrans, FuType.matrix, false, false)
      check(mwait(2), MatOp.mwait, FuType.matrix, false, false, unit = 2)
      check(mfence, MatOp.mfence, FuType.system, false, true, checkUnit = false)
      check(mbarrier(17), MatOp.mbarrier, FuType.system, false, true, checkUnit = false)

      // Illegal matrix encodings remain illegal instructions.
      for (bad <- Seq(msetmode(3, 0), msetmode(0, 1), mload(1, 3, 0), mmul(3),
        mwait(3), BigInt(0x5b) | (4 << 25))) {
        c.io.in.inst.poke(bad)
        c.io.out.exception.expect(true)
        c.io.out.cause.expect(FaultCode.illegal)
        c.io.out.badAddr.expect(bad)
      }
    }
  }
}
