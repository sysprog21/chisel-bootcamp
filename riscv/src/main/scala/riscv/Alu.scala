package riscv

import chisel3._
import chisel3.util._

/** The ALU: `out` is `a op b`. Shifts use the low log2(width) bits of `b`; `Slt` and `Sltu` give 1
  * or 0 (signed and unsigned comparison).
  */
class Alu(width: Int = 32) extends Module {
  val io = IO(new Bundle {
    val op  = Input(AluOp())
    val a   = Input(UInt(width.W))
    val b   = Input(UInt(width.W))
    val out = Output(UInt(width.W))
  })

  io.out := ??? // Exercise: compute `a op b` for every AluOp
}
