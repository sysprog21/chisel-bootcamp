package riscv

import chisel3._
import chisel3.util._

/** The address range of a device on the bus. */
case class Region(base: Long, size: Long) {
  def contains(addr: UInt): Bool = addr >= base.U && addr < (base + size).U
}

/** A bus with one master (the core's data port) and devices at address ranges. It takes one
  * request at a time: it accepts a request (`req.fire`) when it is idle, registers it, and passes it
  * the next cycle to the device whose range holds the address, which answers when it can (a device
  * may make the master wait: backpressure). The device sees the request (`req.valid`) in every
  * cycle until it answers (`resp.valid`), and must act on it once, in the cycle it answers, as
  * `Ram`, `Uart` and `Delay` do; the bus ignores the device's `req.ready`. The answer goes
  * back to the master; then the bus takes the next request, so requests are answered in order. An
  * address that no device holds is answered with an error.
  */
class Bus(regions: Seq[Region]) extends Module {
  require(regions.nonEmpty && regions.forall(_.size > 0), "regions must not be empty")
  // the bus routes the word address (the master's requests are whole words with byte strobes)
  require(regions.forall(r => r.base % 4 == 0 && r.size % 4 == 0), "regions must be whole words")
  for (Seq(a, b) <- regions.sortBy(_.base).sliding(2))
    require(a.base + a.size <= b.base, s"regions overlap: $a and $b")
  val io = IO(new Bundle {
    val master  = Flipped(new MemPort)
    val devices = Vec(regions.size, new MemPort)
  })

  val busy     = RegInit(false.B)
  val request  = Reg(new MemReq)
  val selected = Reg(UInt(log2Ceil(regions.size max 2).W))
  val unmapped = Reg(Bool())

  io.master.req.ready := !busy
  when(io.master.req.fire) {
    busy    := true.B
    request := io.master.req.bits
    val hits = regions.map(_.contains(io.master.req.bits.addr))
    selected := PriorityEncoder(hits)
    unmapped := !hits.reduce(_ || _)
  }

  for ((device, i) <- io.devices.zipWithIndex) {
    device.req.valid := busy && !unmapped && selected === i.U
    device.req.bits  := request
  }
  val answer = io.devices(selected).resp
  io.master.resp.valid      := busy && (unmapped || answer.valid)
  io.master.resp.bits.rdata := answer.bits.rdata
  io.master.resp.bits.error := unmapped || answer.bits.error
  when(io.master.resp.valid)(busy := false.B)
}
