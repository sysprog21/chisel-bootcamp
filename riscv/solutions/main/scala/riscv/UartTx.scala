package riscv

import chisel3._
import chisel3.util._

/** A UART transmitter: sends a byte on `txd` as a frame of 10 bits, each `divider` clock cycles
  * long: a start bit (0), the 8 data bits, least significant first, and a stop bit (1). Between
  * frames `txd` is 1. It takes a byte (`send`) only when it is not `busy`.
  */
class UartTx(divider: Int) extends Module {
  require(divider >= 1)
  val io = IO(new Bundle {
    val send = Input(Valid(UInt(8.W)))
    val busy = Output(Bool())
    val txd  = Output(Bool())
  })

  val frame = RegInit(1.U(10.W))                    // the bits still to send, the next one in bit 0
  val left  = RegInit(0.U(4.W))                     // how many
  val count = RegInit(0.U(log2Ceil(divider + 1).W)) // cycles left of the current bit
  io.busy := left =/= 0.U
  io.txd  := Mux(io.busy, frame(0), true.B)

  when(!io.busy && io.send.valid) {
    frame := 1.U(1.W) ## io.send.bits ## 0.U(1.W)
    left  := 10.U
    count := (divider - 1).U
  }.elsewhen(io.busy) {
    when(count === 0.U) {
      frame := frame >> 1
      left  := left - 1.U
      count := (divider - 1).U
    }.otherwise(count := count - 1.U)
  }
}
