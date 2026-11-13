package riscv

import chisel3._

/** The operations of the RV32I ALU (the R-type and I-type arithmetic instructions). */
object AluOp extends ChiselEnum {
  val Add, Sub, Sll, Slt, Sltu, Xor, Srl, Sra, Or, And = Value

  /** The RV32I instruction that does `op` on two registers (`add`, `sltu`, ...). */
  def mnemonic(op: Type): String = op match {
    case Add  => "add"
    case Sub  => "sub"
    case Sll  => "sll"
    case Slt  => "slt"
    case Sltu => "sltu"
    case Xor  => "xor"
    case Srl  => "srl"
    case Sra  => "sra"
    case Or   => "or"
    case And  => "and"
    case _    => throw new IllegalArgumentException(s"not a literal AluOp: $op") // a hardware value
  }
}
