package riscv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class SynthSpec extends AnyFlatSpec with ChiselSim {

  "Ulx3sTop" should "boot its program into the RAM, run it, and print through the UART" in {
    val divider = 4
    simulate(new Ulx3sTop(Asm(Programs.source("programs/src", "hello")), clockHz = 100 * divider, baud = 100)) { c =>
      c.reset.poke(true.B)
      c.clock.step()
      c.reset.poke(false.B)
      val out = Soc.listen(c.clock, c.ftdi_rxd, (c.led.peek().litValue & 2) != 0, divider, maxCycles = 20000)
      assert(out == "Hello, world!\n", out)
      assert((c.led.peek().litValue & 3) == 3, "LED 0: booted; LED 1: halted")
    }
  }

  "Synth" should "synthesize the decoders, the hand-written one smaller" in {
    assume(Yosys.available, "yosys is not on the PATH")
    val (table, hand) = (Synth.cells(new Decoder, "ice40"), Synth.cells(new SwitchDecoder, "ice40"))
    assert(hand.luts > 0 && hand.luts < table.luts && table.flipFlops == 0, s"$table, $hand")
  }

  it should "fit the board's system in the smallest ULX3S (LFE5U-12F: 12K LUTs, 32 block RAMs)" in {
    assume(Yosys.available, "yosys is not on the PATH")
    val c = Synth.cells(new Ulx3sTop(Asm(Programs.source("programs/src", "hello"))), "ecp5")
    assert(c.luts > 1000 && c.luts + 2 * c.lutRams < 12000 && c.blockRams <= 32 && c.flipFlops > 0, c.toString)
  }

  "Synth.lint" should "run Verilator's lint on a design" in {
    val warnings = Synth.lint(new Decoder) // a Module whose clock and reset the decoder does not use
    assert(warnings.nonEmpty && warnings.forall(_.contains("UNUSEDSIGNAL")), warnings.mkString("\n"))
  }
}
