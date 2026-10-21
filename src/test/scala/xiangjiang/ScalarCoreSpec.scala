package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

class ScalarCoreSpec extends AnyFunSuite with ChiselSim {
  test("executes independent work behind a delayed load without exposing speculative results") {
    val p = XJParams(nRob = 8, nIssue = 4, nXRes = 6, nMemq = 4)
    simulate(new ScalarCore(p)) { dut =>
      import Rv32._
      val program = Seq(lw(1, 0, 0), addi(2, 1, 1), addi(3, 0, 7),
        sw(2, 0, 64), addi(2, 0, 99), addi(4, 2, 1), lw(5, 0, 64), fence, halt)
      dut.io.start.poke(false); dut.io.ack.poke(false)
      dut.io.fetchReq.ready.poke(true); dut.io.fetchResp.valid.poke(false)
      dut.io.fetchResp.bits.pc.poke(0); dut.io.fetchResp.bits.inst.poke(0)
      dut.io.fetchResp.bits.exception.poke(false); dut.io.fetchResp.bits.cause.poke(0)
      dut.io.fetchResp.bits.badAddr.poke(0)
      dut.io.dataReq.ready.poke(true); dut.io.dataResp.valid.poke(false)
      dut.io.dataResp.bits.data.poke(0); dut.io.dataResp.bits.error.poke(false)
      var pending: Option[(Int, BigInt, Boolean)] = None
      var responseValid = false
      var memory64 = BigInt(0)
      val memoryOrder = scala.collection.mutable.ArrayBuffer.empty[(Boolean, Long)]
      val commits = scala.collection.mutable.ArrayBuffer.empty[Long]
      var sawEarlyCompute = false
      var sawEarlyAddress = false
      var cycle = 0
      def tick(): Unit = {
        val fetch = if (dut.io.fetchReq.valid.peek().litToBoolean)
          Some(dut.io.fetchReq.bits.peek().litValue.toInt) else None
        if (dut.io.retire.valid.peek().litToBoolean)
          commits += dut.io.retire.bits.uop.pc.peek().litValue.toLong
        if (dut.io.writeback.valid.peek().litToBoolean && commits.isEmpty &&
          dut.io.writeback.bits.data.peek().litValue == 7) {
          sawEarlyCompute = true
          dut.io.debugRegs(3).expect(0)
        }
        if (dut.io.aguIssued.valid.peek().litToBoolean && memoryOrder.size == 1 && commits.isEmpty)
          sawEarlyAddress = true
        val responseTaken = responseValid && dut.io.dataResp.ready.peek().litToBoolean
        if (responseTaken) { pending = None; responseValid = false }
        if (dut.io.dataReq.valid.peek().litToBoolean) {
          assert(pending.isEmpty, "two active MemQ operations")
          val addr = dut.io.dataReq.bits.addr.peek().litValue.toLong
          val store = dut.io.dataReq.bits.write.peek().litToBoolean
          val data = dut.io.dataReq.bits.data.peek().litValue
          memoryOrder += ((store, addr))
          if (store) {
            assert(commits.take(3).toSeq == Seq(0L, 4L, 8L), "store authorized before preceding commits")
            assert(data == 42, "store reread a younger WAW version")
            memory64 = data
          }
          val value = if (store) BigInt(0) else if (addr == 0) BigInt(41) else memory64
          pending = Some((if (memoryOrder.size == 1) 35 else 4, value, false))
        }
        dut.clock.step(); cycle += 1
        dut.io.fetchResp.valid.poke(fetch.isDefined)
        fetch.foreach { pc =>
          assert(pc % 4 == 0 && pc / 4 < program.size)
          dut.io.fetchResp.bits.pc.poke(pc)
          dut.io.fetchResp.bits.inst.poke(program(pc / 4))
        }
        pending = pending.map { case (delay, value, error) =>
          if (delay > 0) (delay - 1, value, error) else (delay, value, error)
        }
        responseValid = pending.exists(_._1 == 0)
        dut.io.dataResp.valid.poke(responseValid)
        pending.foreach { case (_, value, error) =>
          dut.io.dataResp.bits.data.poke(value); dut.io.dataResp.bits.error.poke(error)
        }
      }
      dut.io.start.poke(true); tick(); dut.io.start.poke(false)
      while (!dut.io.status.done.peek().litToBoolean && cycle < 500) tick()
      dut.io.status.done.expect(true); dut.io.status.fault.expect(false)
      assert(sawEarlyCompute, "ALU must bypass an older blocked dependency")
      assert(sawEarlyAddress, "AGU must not wait for store data")
      assert(commits.toSeq == program.indices.map(_ * 4L))
      assert(memoryOrder.toSeq == Seq((false, 0L), (true, 64L), (false, 64L)))
      dut.io.debugRegs(1).expect(41); dut.io.debugRegs(2).expect(99)
      dut.io.debugRegs(3).expect(7); dut.io.debugRegs(4).expect(100)
      dut.io.debugRegs(5).expect(42)
    }
  }

  test("records a delayed memory error and blocks younger stores before restart") {
    val p = XJParams()
    simulate(new ScalarCore(p)) { dut =>
      import Rv32._
      // The older bad load blocks memory successors; no later write may start.
      val program = Seq(addi(1, 0, 19), lw(2, 0, 0), addi(3, 0, 23), sw(3, 0, 64), halt)
      dut.io.start.poke(false); dut.io.ack.poke(false)
      dut.io.fetchReq.ready.poke(true); dut.io.fetchResp.valid.poke(false)
      dut.io.fetchResp.bits.pc.poke(0); dut.io.fetchResp.bits.inst.poke(0)
      dut.io.fetchResp.bits.exception.poke(false); dut.io.fetchResp.bits.cause.poke(0)
      dut.io.fetchResp.bits.badAddr.poke(0)
      dut.io.dataReq.ready.poke(true); dut.io.dataResp.valid.poke(false)
      dut.io.dataResp.bits.data.poke(0); dut.io.dataResp.bits.error.poke(true)
      var delay = -1
      var requests = 0
      var cycles = 0
      def tick(): Unit = {
        val f = if (dut.io.fetchReq.valid.peek().litToBoolean)
          Some(dut.io.fetchReq.bits.peek().litValue.toInt) else None
        if (dut.io.dataReq.valid.peek().litToBoolean) {
          dut.io.dataReq.bits.write.expect(false)
          requests += 1
          assert(requests == 1)
          delay = 30
        }
        if (dut.io.dataResp.valid.peek().litToBoolean && dut.io.dataResp.ready.peek().litToBoolean) delay = -1
        dut.clock.step(); cycles += 1
        dut.io.fetchResp.valid.poke(f.isDefined)
        f.foreach { pc => dut.io.fetchResp.bits.pc.poke(pc); dut.io.fetchResp.bits.inst.poke(program(pc / 4)) }
        if (delay > 0) delay -= 1
        dut.io.dataResp.valid.poke(delay == 0)
      }
      dut.io.start.poke(true); tick(); dut.io.start.poke(false)
      while ((!dut.io.status.fault.peek().litToBoolean || dut.io.status.busy.peek().litToBoolean) && cycles < 500) tick()
      dut.io.status.busy.expect(false); dut.io.status.fault.expect(true)
      dut.io.status.faultPc.expect(4); dut.io.status.faultCause.expect(5)
      dut.io.debugRegs(1).expect(19); dut.io.debugRegs(2).expect(0); dut.io.debugRegs(3).expect(0)
      assert(requests == 1)
      dut.io.ack.poke(true); tick(); dut.io.ack.poke(false)
      dut.io.status.fault.expect(false)
    }
  }
}
