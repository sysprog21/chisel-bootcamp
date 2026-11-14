package riscv

import chisel3._
import chisel3.util._

/** A register value that an older instruction is about to write: its register number and the value. */
class Result extends Bundle {
  val rd    = UInt(5.W)
  val value = UInt(32.W)
}

/** The value of register `rs` for the instruction in EX: the newest result an older instruction in
  * the pipeline has for it (in EX/MEM, then in MEM/WB), or else the value read from the register file
  * in ID. A result for x0 does not count: x0 is always 0.
  */
class Forward extends Module {
  val io = IO(new Bundle {
    val rs       = Input(UInt(5.W))
    val regValue = Input(UInt(32.W))        // read in ID
    val exMem    = Input(Valid(new Result)) // the instruction one ahead
    val memWb    = Input(Valid(new Result)) // the instruction two ahead
    val value    = Output(UInt(32.W))
  })

  io.value := ??? // Exercise
}
