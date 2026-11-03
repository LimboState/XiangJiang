package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.backend.fu.MatrixArray

class MatrixStateSpec extends AnyFunSuite with ChiselSim {
  test("matrix working results remain invisible until publish and flush preserves committed ACC") {
    simulate(new MatrixArray(false)) { c =>
      c.io.flush.poke(false); c.io.loadBeat.valid.poke(false)
      c.io.loadBeat.bits.buffer.poke(0); c.io.loadBeat.bits.beat.poke(0); c.io.loadBeat.bits.data.poke(0)
      c.io.start.valid.poke(false); c.io.start.bits.op.poke(MatOp.mtrans)
      c.io.publish.valid.poke(false); c.io.publish.bits.poke(0); c.io.storeBeat.poke(0)
      c.clock.step()
      c.io.validBuffers.foreach(_.expect(false)); c.io.storeData.expect(0)
      // Load a complete input tile into staging. Its valid bit is still false.
      val tile = (0 until 64).foldLeft(BigInt(0))((v, i) => v | (BigInt(i) << (8 * i)))
      c.io.loadBeat.valid.poke(true); c.io.loadBeat.bits.data.poke(tile); c.clock.step()
      c.io.loadBeat.valid.poke(false); c.io.validBuffers(0).expect(false)
      c.io.publish.valid.poke(true); c.clock.step(); c.io.publish.valid.poke(false)
      c.io.validBuffers(0).expect(true)
      def compute(): Unit = {
        c.io.start.valid.poke(true); c.io.start.ready.expect(true); c.clock.step()
        c.io.start.valid.poke(false); c.clock.step(64); c.io.busy.expect(false)
      }
      compute()
      c.io.validBuffers(2).expect(false); c.io.storeData.expect(0)
      c.io.publish.bits.poke(2); c.io.publish.valid.poke(true); c.clock.step()
      c.io.publish.valid.poke(false); c.io.validBuffers(2).expect(true)
      val expected = (0 until 16).foldLeft(BigInt(0)) { (v, i) =>
        v | (BigInt((i % 8) * 8 + i / 8) << (32 * i))
      }
      c.io.storeData.expect(expected)
      // A different uncommitted input and an interrupted computation cannot change ACC.
      c.io.loadBeat.valid.poke(true); c.io.loadBeat.bits.data.poke(0); c.clock.step()
      c.io.loadBeat.valid.poke(false); c.io.flush.poke(true); c.clock.step(); c.io.flush.poke(false)
      c.io.storeData.expect(expected); c.io.validBuffers(0).expect(true); c.io.validBuffers(2).expect(true)
      c.io.start.valid.poke(true); c.clock.step(); c.io.start.valid.poke(false); c.clock.step(7)
      c.io.flush.poke(true); c.clock.step(); c.io.flush.poke(false)
      c.io.busy.expect(false); c.io.storeData.expect(expected)
    }
  }
}
