package riscv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** Unit tests of the RAM and of the slow memory of the bus, which have no exercise in them and so
  * pass from the start (Module 2.6 shows this file as an example of tests in files).
  */
class MemorySpec extends AnyFlatSpec with ChiselSim {

  "Ram" should "return the word written through its load port, and change only the bytes a store selects" in {
    simulate(new Ram(words = 16)) { c =>
      def request(addr: Long, wdata: Long = 0, wstrb: Int = 0) = {
        c.io.dmem.req.valid.poke(true.B)
        c.io.dmem.req.bits.addr.poke(addr.U)
        c.io.dmem.req.bits.wdata.poke(wdata.U)
        c.io.dmem.req.bits.wstrb.poke(wstrb.U)
      }
      c.io.load.valid.poke(true.B) // as CoSim.load writes a program
      c.io.load.bits.addr.poke((Ram.base + 4).U)
      c.io.load.bits.data.poke(0x11223344L.U)
      c.clock.step()
      c.io.load.valid.poke(false.B)
      request(Ram.base + 4)
      c.io.dmem.resp.bits.rdata.expect(0x11223344L.U)
      c.io.dmem.resp.bits.error.expect(false.B)
      request(Ram.base + 4, wdata = 0xaa0000L, wstrb = 0x4) // byte 2
      c.clock.step()
      request(Ram.base + 4)
      c.io.dmem.resp.bits.rdata.expect(0x11aa3344L.U, "only byte 2 changed")
      request(Ram.base - 4) // just below the RAM
      c.io.dmem.resp.bits.error.expect(true.B)
      request(Ram.base + 68, wdata = 0xffffffffL, wstrb = 0xf) // past the 16 words, where word 1 aliases
      c.io.dmem.resp.bits.error.expect(true.B)
      c.clock.step()
      request(Ram.base + 4)
      c.io.dmem.resp.bits.rdata.expect(0x11aa3344L.U, "a store outside the RAM writes nothing")
    }
  }

  "Delay" should "pass a request on after its latency, and take it with the answer" in {
    simulate(new Delay(3)) { c =>
      c.io.in.req.valid.poke(true.B)
      c.io.out.req.ready.poke(true.B)
      c.io.out.resp.valid.poke(false.B)
      for (cycle <- 0 until 3) {
        c.io.out.req.valid.expect(false.B, s"cycle $cycle")
        c.clock.step()
      }
      for (cycle <- 3 until 6) { // a slow device: it answers at cycle 6
        c.io.out.req.valid.expect(true.B, s"cycle $cycle")
        c.io.in.req.ready.expect(false.B, "taken only with the answer")
        c.clock.step()
      }
      c.io.out.resp.valid.poke(true.B)
      c.io.in.resp.valid.expect(true.B)
      c.io.in.req.ready.expect(true.B)
      c.clock.step()
      c.io.out.resp.valid.poke(false.B)
      c.io.out.req.valid.expect(false.B, "the next request waits its latency again")
    }
  }
}
