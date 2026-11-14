package riscv

import chisel3._
import chisel3.util._

/** A forwarding unit next to its specification, written another way: `ok` is 1 when they agree.
  * A formal tool can prove that `ok` is 1 for every input (`Formal.prove`). The specification
  * says "the newest result wins" with Chisel's last-connect rule: the older result is connected
  * first, the newer one after it, so the newer one wins when both write `rs`.
  */
class ForwardCheck(gen: => Forward) extends Module {
  val io = IO(new Bundle {
    val rs       = Input(UInt(5.W))
    val regValue = Input(UInt(32.W))
    val exMem    = Input(Valid(new Result))
    val memWb    = Input(Valid(new Result))
    val ok       = Output(Bool())
  })
  val dut = Module(gen)
  dut.io.rs       := io.rs
  dut.io.regValue := io.regValue
  dut.io.exMem    := io.exMem
  dut.io.memWb    := io.memWb
  val expected = WireDefault(io.regValue)
  when(io.rs =/= 0.U) { // x0 is always 0, as the register file reads it
    when(io.memWb.valid && io.memWb.bits.rd === io.rs)(expected := io.memWb.bits.value) // two ahead: older
    when(io.exMem.valid && io.exMem.bits.rd === io.rs)(expected := io.exMem.bits.value) // one ahead: newer
  }
  io.ok := dut.io.value === expected
}
