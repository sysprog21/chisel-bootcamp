package riscv

import chisel3._
import chisel3.simulator.PeekPokeAPI._

/** A small system on a chip: the forwarding core, whose data port goes through a `Bus` to the RAM
  * and a `Uart`; instructions come straight from the RAM's instruction port (which answers in the
  * same cycle, as `ForwardingCore` needs). `txd` is the UART's serial output. `ramWords` is the size
  * of the RAM (a board has less memory than the simulations).
  */
class SocSystem(val divider: Int = 4, ramWords: Int = Ram.bytes / 4) extends RiscvSystem {
  val txd    = IO(Output(Bool()))
  val core   = Module(new ForwardingCore)
  val ram    = Module(new Ram(ramWords))
  val uart   = Module(new Uart(divider))
  val memory = Region(Ram.base, 4L * ramWords)
  val bus    = Module(new Bus(Seq(memory, Region(Uart.base, Uart.bytes))))
  ram.io.imem <> core.io.imem
  bus.io.master <> core.io.dmem
  ram.io.dmem <> bus.io.devices(0)
  uart.io.port <> bus.io.devices(1)
  ram.io.load := io.load
  io.retire   := core.io.retire
  io.halted   := core.io.halted
  io.dbg <> core.io.dbg
  txd := uart.io.txd

  override def model(image: Seq[Long]): IsaSim = new IsaSim(image, size = 4 * ramWords, devices = Seq(new UartModel))
}

object Soc {

  /** Runs the loaded program until it halts and the UART has sent everything; returns what the UART
    * printed (decoded from `txd`). Fails, with what it printed so far, if that takes over `maxCycles`.
    */
  def run(c: SocSystem, maxCycles: Int = 200000): String =
    listen(c.clock, c.txd, c.io.halted.peek().litToBoolean, c.divider, maxCycles)

  /** As `run`, for any design with a UART line `txd` (`divider` cycles per bit) and a way to tell
    * that its program has `halted`: the cycles go on until it has, and the line has been idle for a
    * whole frame. Fails if that has not happened within `maxCycles` cycles.
    */
  def listen(clock: Clock, txd: Bool, halted: => Boolean, divider: Int, maxCycles: Int = 200000): String = {
    val receiver = new UartReceiver(divider)
    val out      = new StringBuilder
    var cycles   = 0
    var idle     = 0 // cycles since the core halted with the line idle
    val frame    = 12 * divider
    while (idle < frame && cycles < maxCycles) {
      val line = txd.peek().litToBoolean
      out ++= receiver.sample(line)
      if (halted && line) idle += 1 else idle = 0
      clock.step()
      cycles += 1
    }
    if (idle < frame) {
      val why = if (halted) "the UART is still sending" else "no halt"
      throw new IllegalStateException(s"not finished within $maxCycles cycles ($why); printed so far: $out")
    }
    out.toString
  }
}
