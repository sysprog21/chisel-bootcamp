package riscv

import chisel3._
import chisel3.util._

/** The system of Module 5.8 on a ULX3S board (Lattice ECP5 FPGA, 25 MHz clock): it boots by copying
  * `program` (an image from `Asm`) from a ROM into the RAM, holding the system in reset meanwhile,
  * then runs it. The UART sends to the board's USB serial port at `baud` bits per second, and the
  * LEDs show that the program has booted (LED 0) and halted (LED 1), and the 6 low bits of a0
  * (LEDs 2-7). A program must fit in the RAM and keep its stack there (`hello.S` does; `fact.S`
  * puts its stack at the top of the 64 KB of the simulations). The port names are those of
  * `riscv/fpga/ulx3s.lpf`, which gives their pins. Registers start at their reset values when the
  * FPGA is configured, so the boot starts then, and again on a reset (button F1, `btn[1]`, which two
  * flip-flops synchronize to the clock: it changes at any time, not at a clock edge).
  */
class Ulx3sTop(program: Seq[Long], ramWords: Int = 1024, clockHz: Int = 25000000, baud: Int = 115200) extends Module {
  require(program.nonEmpty && program.size <= ramWords, "the program must fit in the RAM")
  val led      = IO(Output(UInt(8.W)))
  val ftdi_rxd = IO(Output(Bool())) // the board's serial input (the FTDI chip receives): our txd

  val pressed = withReset(false.B)(RegNext(RegNext(reset.asBool))) // the button, synchronized
  val rom     = VecInit((program :+ 0L).map(_.U(32.W)))            // a word more, read when `next` = program.size
  val next   = withReset(pressed)(RegInit(0.U(log2Ceil(program.size + 1).W))) // the next word to copy
  val booted = next === program.size.U
  when(!booted)(next := next + 1.U)

  val soc = withReset(pressed || !booted)(Module(new SocSystem(clockHz / baud, ramWords = ramWords)))
  soc.io.load.valid     := !booted
  soc.io.load.bits.addr := Ram.base.U + (next << 2)
  soc.io.load.bits.data := rom(next)
  soc.io.dbg.addr       := 10.U // a0
  ftdi_rxd              := soc.txd
  led                   := Cat(soc.io.dbg.data(5, 0), soc.io.halted, booted)
}

/** Writes the SystemVerilog of [[Ulx3sTop]] for `programs/src/<name>.S` (hello by default), as
  * Yosys reads it, to `fpga/build/Ulx3sTop.sv`: `cd riscv && sbt "runMain riscv.Ulx3s hello"`.
  */
object Ulx3s extends App {
  val name = args.headOption.getOrElse("hello")
  require(name.matches("[A-Za-z0-9_-]+"), s"not the name of a program of programs/src: $name")
  val riscv = if (os.isDir(os.pwd / "fpga")) os.pwd else os.pwd / "riscv" // sbt runs in riscv/
  val out   = riscv / "fpga" / "build" / "Ulx3sTop.sv"
  os.write.over(
    out,
    Yosys.verilog(new Ulx3sTop(Asm(Programs.source("programs/src", name)))),
    createFolders = true
  )
  println(s"wrote $out")
}
