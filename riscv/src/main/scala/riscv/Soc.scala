package riscv

import chisel3._
import chisel3.simulator.PeekPokeAPI._

/** A small system on a chip: the forwarding core, whose data port goes through a `Bus` to the RAM
  * and a `Uart`; instructions come straight from the RAM's instruction port (which answers in the
  * same cycle, as `ForwardingCore` needs). `txd` is the UART's serial output.
  */
class SocSystem(val divider: Int = 4) extends RiscvSystem {
  val txd  = IO(Output(Bool()))
  val core = Module(new ForwardingCore)
  val ram  = Module(new Ram)
  val uart = Module(new Uart(divider))
  val bus  = Module(new Bus(Seq(Region(Ram.base, Ram.bytes), Region(Uart.base, Uart.bytes))))
  ram.io.imem <> core.io.imem
  bus.io.master <> core.io.dmem
  ram.io.dmem <> bus.io.devices(0)
  uart.io.port <> bus.io.devices(1)
  ram.io.load := io.load
  io.retire   := core.io.retire
  io.halted   := core.io.halted
  io.dbg <> core.io.dbg
  txd := uart.io.txd

  override def model(image: Seq[Long]): IsaSim = new IsaSim(image, devices = Seq(new UartModel))
}

object Soc {

  /** Runs the loaded program until it halts and the UART has sent everything; returns what the UART
    * printed (decoded from `txd`).
    */
  def run(c: SocSystem, maxCycles: Int = 200000): String = {
    val receiver = new UartReceiver(c.divider)
    val out      = new StringBuilder
    var cycles   = 0
    var idle     = 0 // cycles since the core halted with the line idle
    while (idle < 12 * c.divider && cycles < maxCycles) {
      val line = c.txd.peek().litToBoolean
      out ++= receiver.sample(line)
      if (c.io.halted.peek().litToBoolean && line) idle += 1 else idle = 0
      c.clock.step()
      cycles += 1
    }
    out.toString
  }
}
