package riscv

import chisel3._

object Uart {

  /** Where the UART is on the bus: its data register, then its status register at +4. */
  val base: Long = 0x10000000L
  val bytes: Int = 8 // the two registers
}

/** A UART on the bus: writing a byte to its data register (`Uart.base`) sends it on `txd`; while
  * the transmitter is still sending the previous byte, the write is not answered, so the core waits
  * (backpressure: a program needs no status loop). Its status register (`Uart.base + 4`) reads 1
  * while the transmitter is busy (the golden model's `UartModel` sends at once, so its status reads
  * 0: a program that reads the status retires different values on a core). Writes to the status
  * register, and to the other bytes of the data register, do nothing.
  */
class Uart(divider: Int) extends Module {
  val io = IO(new Bundle {
    val port = Flipped(new MemPort)
    val txd  = Output(Bool())
  })
  val tx     = Module(new UartTx(divider))
  val req    = io.port.req
  val data   = req.bits.addr(2) === 0.U
  val write  = req.valid && req.bits.wstrb.orR
  val toData = write && data && req.bits.wstrb(0) // a byte for the data register (byte lane 0)
  val sends  = toData && !tx.io.busy
  req.ready               := true.B
  io.port.resp.valid      := req.valid && !(toData && tx.io.busy)
  io.port.resp.bits.rdata := Mux(data, 0.U, tx.io.busy.asUInt)
  io.port.resp.bits.error := false.B
  tx.io.send.valid        := sends
  tx.io.send.bits         := req.bits.wdata(7, 0) // the byte at the register's address (byte lane 0)
  io.txd                  := tx.io.txd
}

/** A UART receiver in software, for testbenches: fed the `txd` line once per clock cycle, it
  * returns each byte when its stop bit has been sent. A frame whose stop bit is 0 counts as a
  * framing error (and no byte); the receiver then waits for the line to go back to 1.
  */
class UartReceiver(divider: Int) {
  private var countdown = -1 // cycles to the middle of the next bit; -1: idle; -2: after an error
  private var bit       = 0  // 1 to 8 the data bits, 9 the stop bit
  private var byte      = 0
  var framingErrors     = 0

  def sample(txd: Boolean): Option[Char] = countdown match {
    case -2 => if (txd) countdown = -1; None // wait for the line to be idle again
    case -1 =>
      // a start bit began: the middle of data bit 0 is a bit and a half later
      if (!txd) { countdown = divider + divider / 2 - 1; bit = 0; byte = 0 }
      None
    case n if n > 0 => countdown -= 1; None
    case _          =>
      countdown = divider - 1
      bit += 1
      if (bit <= 8) { if (txd) byte |= 1 << (bit - 1); None }
      else if (txd) { countdown = -1; Some(byte.toChar) } // the stop bit
      else { framingErrors += 1; countdown = -2; None }
  }
}

/** The golden model of the UART: a byte written to its data register (`base`) is
  * sent at once, and its status register (`base + 4`) reads 0 (never busy).
  */
class UartModel(val base: Long = Uart.base) extends Device {
  val size                                            = Uart.bytes.toLong
  val output                                          = new StringBuilder
  def read(addr: Long, bytes: Int): Long              = 0
  def write(addr: Long, bytes: Int, data: Long): Unit = if (addr == base) output += (data & 0xff).toChar
}
