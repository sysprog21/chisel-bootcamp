package riscv

import chisel3._

/** The operations of the RV32I ALU (the R-type and I-type arithmetic instructions). */
object AluOp extends ChiselEnum {
  val Add, Sub, Sll, Slt, Sltu, Xor, Srl, Sra, Or, And = Value
}
