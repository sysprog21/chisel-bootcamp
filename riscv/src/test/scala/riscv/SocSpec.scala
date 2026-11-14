package riscv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class SocSpec extends AnyFlatSpec with ChiselSim {

  "UartTx" should "send each byte as a start bit, 8 data bits (LSB first) and a stop bit" in {
    for (divider <- Seq(1, 4, 7)) simulate(new UartTx(divider)) { c =>
      val receiver = new UartReceiver(divider)
      val text     = "Hi!\u0000ÿ"
      val out      = new StringBuilder
      var pending  = text.toList
      var cycles   = 0
      while (out.size < text.size && cycles < 20 * divider * text.size) {
        val busy = c.io.busy.peek().litToBoolean
        c.io.send.valid.poke((!busy && pending.nonEmpty).B)
        c.io.send.bits.poke(pending.headOption.getOrElse(0.toChar).toInt.U)
        if (!busy && pending.nonEmpty) pending = pending.tail
        out ++= receiver.sample(c.io.txd.peek().litToBoolean)
        c.clock.step()
        cycles += 1
      }
      assert(out.toString == text && receiver.framingErrors == 0, s"divider $divider")
      // a frame takes 10 bits of `divider` cycles, and the next byte is taken one cycle after it
      assert(cycles <= (10 * divider + 1) * text.size + 2 * divider, s"divider $divider: $cycles cycles")
    }
  }

  "SocSystem" should "print Hello, world through the UART" in {
    simulate(new SocSystem) { c =>
      val image = Asm(Programs.source("programs/src", "hello"))
      CoSim.compare(c, image).foreach(d => fail(d)) // on the bus, as on the model
      CoSim.load(c, image)
      assert(Soc.run(c) == "Hello, world!\n")
      val uart = new UartModel // and the golden model printed the same
      new IsaSim(image, devices = Seq(uart)).run()
      assert(uart.output.toString == "Hello, world!\n")
    }
  }

  it should "run programs through the bus as the golden model does" in {
    simulate(new SocSystem) { c =>
      for ((name, image) <- Programs.corpus(seeds = 1L to 5L))
        CoSim.compare(c, image).foreach(d => fail(s"$name: $d"))
    }
  }

  it should "answer an address that no device holds with an error, and read the UART's status" in {
    simulate(new SocSystem) { c =>
      val programs = Seq(
        "li t0, 0x20000000\nlw a0, 0(t0)",                                // unmapped: halts with BadAddress
        "li t0, 0x10000000\nli a0, 65\nsb a0, 0(t0)\nlw a1, 4(t0)\necall" // status (the model reads 0)
      )
      CoSim.compare(c, Asm(programs(0))).foreach(d => fail(d))
      CoSim.load(c, Asm(programs(1)))
      val trace = CoSim.run(c)
      assert(trace.last.halt.contains(Halt.Ecall))
      assert(CoSim.reg(c, riscv.Reg("a1")) == 1, "the UART is still sending the byte")
    }
  }
}
