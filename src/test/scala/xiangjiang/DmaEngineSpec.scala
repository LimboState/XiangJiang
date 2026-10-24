package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import device.DmaEngine
import org.scalatest.funsuite.AnyFunSuite

class DmaEngineSpec extends AnyFunSuite with ChiselSim {
  private val p = XJParams(bankBytes = 256)

  private def until(dut: DmaEngine, condition: => Boolean, limit: Int = 1000): Unit = {
    var cycles = 0
    while (!condition && cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(condition, s"timed out after $cycles cycles")
  }

  private def initialize(dut: DmaEngine): Unit = {
    dut.io.start.poke(false.B)
    dut.io.clear.poke(false.B)
    dut.io.dir.poke(false.B)
    dut.io.hostAddr.poke(0.U)
    dut.io.localAddr.poke(0.U)
    dut.io.length.poke(0.U)
    dut.io.localReq.ready.poke(true.B)
    dut.io.localResp.valid.poke(false.B)
    dut.io.localResp.bits.data.poke(0.U)
    dut.io.localResp.bits.error.poke(false.B)
    dut.io.axi.aw.ready.poke(true.B)
    dut.io.axi.w.ready.poke(true.B)
    dut.io.axi.b.valid.poke(false.B)
    dut.io.axi.b.bits.id.poke(0.U)
    dut.io.axi.b.bits.resp.poke(0.U)
    dut.io.axi.ar.ready.poke(true.B)
    dut.io.axi.r.valid.poke(false.B)
    dut.io.axi.r.bits.id.poke(0.U)
    dut.io.axi.r.bits.data.poke(0.U)
    dut.io.axi.r.bits.resp.poke(0.U)
    dut.io.axi.r.bits.last.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  private def start(dut: DmaEngine, host: BigInt, local: Int, bytes: Int, dir: Boolean): Unit = {
    dut.io.hostAddr.poke(host.U)
    dut.io.localAddr.poke(local.U)
    dut.io.length.poke(bytes.U)
    dut.io.dir.poke(dir.B)
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)
  }

  test("host-to-DTCM reads split at 4 KiB and write each 128-bit beat locally") {
    simulate(new DmaEngine(p)) { dut =>
      initialize(dut)
      start(dut, BigInt("1ff0", 16), 0x20, 32, dir = false)

      val localWrites = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
      val burstAddresses = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
      var finished = false
      while (!finished && burstAddresses.size < 3) {
        until(dut, dut.io.axi.ar.valid.peek().litToBoolean)
        val ar = dut.io.axi.ar.bits
        burstAddresses += ((ar.addr.peek().litValue, ar.len.peek().litValue))
        ar.size.expect(4.U)
        ar.burst.expect(1.U)
        dut.clock.step()

        val beats = burstAddresses.last._2.toInt + 1
        for (beat <- 0 until beats) {
          val data = BigInt("10000000000000000000000000000000", 16) + beat + localWrites.size
          dut.io.axi.r.bits.data.poke(data.U)
          dut.io.axi.r.bits.resp.poke(0.U)
          dut.io.axi.r.bits.id.poke(0.U)
          dut.io.axi.r.bits.last.poke((beat == beats - 1).B)
          dut.io.axi.r.valid.poke(true.B)
          until(dut, dut.io.axi.r.ready.peek().litToBoolean)
          dut.io.localReq.valid.expect(true.B)
          dut.io.localReq.bits.write.expect(true.B)
          localWrites += ((dut.io.localReq.bits.addr.peek().litValue, dut.io.localReq.bits.data.peek().litValue))
          dut.clock.step()
          dut.io.axi.r.valid.poke(false.B)
        }
        finished = dut.io.done.peek().litToBoolean
      }

      assert(burstAddresses.toSeq == Seq((BigInt("1ff0", 16), BigInt(0)), (BigInt("2000", 16), BigInt(0))))
      assert(localWrites.map(_._1).toSeq == Seq(BigInt(0x20), BigInt(0x30)))
      assert(localWrites.map(_._2).toSeq == Seq(
        BigInt("10000000000000000000000000000000", 16),
        BigInt("10000000000000000000000000000001", 16)))
      dut.io.done.expect(true.B)
      dut.io.busy.expect(false.B)
      dut.io.error.expect(false.B)
    }
  }

  test("DTCM-to-host reads local beats and sends AXI write data") {
    simulate(new DmaEngine(p)) { dut =>
      initialize(dut)
      start(dut, BigInt("8000", 16), 0x40, 16, dir = true)

      until(dut, dut.io.axi.aw.valid.peek().litToBoolean)
      dut.io.axi.aw.bits.addr.expect("h8000".U)
      dut.io.axi.aw.bits.len.expect(0.U)
      dut.io.axi.aw.bits.size.expect(4.U)
      dut.clock.step()

      until(dut, dut.io.localReq.valid.peek().litToBoolean)
      dut.io.localReq.bits.write.expect(false.B)
      dut.io.localReq.bits.addr.expect("h40".U)
      dut.clock.step()

      val data = BigInt("fedcba98765432100123456789abcdef", 16)
      dut.io.localResp.bits.data.poke(data.U)
      dut.io.localResp.bits.error.poke(false.B)
      dut.io.localResp.valid.poke(true.B)
      until(dut, dut.io.localResp.ready.peek().litToBoolean)
      dut.clock.step()
      dut.io.localResp.valid.poke(false.B)

      until(dut, dut.io.axi.w.valid.peek().litToBoolean)
      dut.io.axi.w.bits.data.expect(data.U)
      dut.io.axi.w.bits.strb.expect("hffff".U)
      dut.io.axi.w.bits.last.expect(true.B)
      dut.clock.step()

      dut.io.axi.b.valid.poke(true.B)
      until(dut, dut.io.axi.b.ready.peek().litToBoolean)
      dut.clock.step()
      dut.io.axi.b.valid.poke(false.B)
      dut.io.done.expect(true.B)
      dut.io.error.expect(false.B)
    }
  }

  test("invalid descriptors and failed AXI reads report sticky errors") {
    simulate(new DmaEngine(p)) { dut =>
      initialize(dut)
      start(dut, BigInt("1000", 16), 0, 0, dir = false)
      dut.io.error.expect(true.B)
      dut.io.errorCode.expect(1.U)
      dut.io.busy.expect(false.B)

      dut.io.clear.poke(true.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)
      dut.io.error.expect(false.B)
      start(dut, BigInt("1000", 16), 0, 16, dir = false)
      until(dut, dut.io.axi.ar.valid.peek().litToBoolean)
      dut.clock.step()

      dut.io.axi.r.bits.id.poke(0.U)
      dut.io.axi.r.bits.resp.poke(2.U)
      dut.io.axi.r.bits.last.poke(true.B)
      dut.io.axi.r.valid.poke(true.B)
      until(dut, dut.io.axi.r.ready.peek().litToBoolean)
      dut.clock.step()
      dut.io.axi.r.valid.poke(false.B)
      dut.io.error.expect(true.B)
      dut.io.errorCode.expect(2.U)
      dut.io.errorAddr.expect("h1000".U)
      dut.io.done.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }
}
