package xiangjiang

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite
import xiangjiang.frontend.Frontend

class FrontendSpec extends AnyFunSuite with ChiselSim {
  test("redirect and restart drain old-epoch responses before reusing request metadata") {
    simulate(new Frontend) { c =>
      c.io.start.poke(false); c.io.stopRun.poke(false); c.io.barrier.poke(false)
      c.io.redirect.valid.poke(false); c.io.redirect.bits.poke(0)
      c.io.request.ready.poke(true); c.io.fetch.ready.poke(false)
      c.io.response.valid.poke(false); c.io.response.bits.pc.poke(0); c.io.response.bits.inst.poke(0x13)
      c.io.response.bits.exception.poke(false); c.io.response.bits.cause.poke(0); c.io.response.bits.badAddr.poke(0)
      c.clock.step(); c.io.start.poke(true); c.clock.step(); c.io.start.poke(false)
      c.io.request.valid.expect(true); c.io.request.bits.expect(0); c.clock.step()
      c.io.redirect.valid.poke(true); c.io.redirect.bits.poke(64); c.clock.step()
      c.io.redirect.valid.poke(false); c.io.request.valid.expect(false)
      // An even number of epoch changes must not make an old response live.
      c.io.stopRun.poke(true); c.clock.step(2); c.io.stopRun.poke(false)
      c.io.start.poke(true); c.clock.step(); c.io.start.poke(false)
      c.io.request.valid.expect(false)
      c.io.response.valid.poke(true); c.clock.step(); c.io.response.valid.poke(false)
      c.io.fetch.valid.expect(false); c.io.request.valid.expect(true); c.io.request.bits.expect(0)
      c.clock.step(); c.io.response.valid.poke(true); c.io.response.bits.inst.poke(0x00100093)
      c.clock.step(); c.io.response.valid.poke(false)
      c.io.fetch.valid.expect(true); c.io.fetch.bits.inst.expect(0x00100093)
      c.io.barrier.poke(true); c.clock.step(3); c.io.request.valid.expect(false)
      c.io.fetch.ready.poke(true); c.clock.step(); c.io.fetch.ready.poke(false)
      c.io.redirect.valid.poke(true); c.io.redirect.bits.poke(128); c.clock.step()
      c.io.redirect.valid.poke(false); c.io.barrier.poke(false)
      c.io.request.valid.expect(true); c.io.request.bits.expect(128)
    }
  }
}
