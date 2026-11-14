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

  val shamt = io.b(log2Ceil(width) - 1, 0)
  io.out := MuxLookup(io.op, 0.U)(
    Seq(
      AluOp.Add  -> (io.a + io.b),
      AluOp.Sub  -> (io.a - io.b),
      AluOp.Sll  -> (io.a << shamt)(width - 1, 0),
      AluOp.Slt  -> (io.a.asSInt < io.b.asSInt),
      AluOp.Sltu -> (io.a < io.b),
      AluOp.Xor  -> (io.a ^ io.b),
      AluOp.Srl  -> (io.a >> shamt),
      AluOp.Sra  -> (io.a.asSInt >> shamt).asUInt,
      AluOp.Or   -> (io.a | io.b),
      AluOp.And  -> (io.a & io.b)
    )
  )
}
