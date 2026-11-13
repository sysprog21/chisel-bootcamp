package riscv

import chisel3._
import chisel3.util._

/** The instruction formats that have an immediate (R-type has none). */
object ImmFormat extends ChiselEnum {
  val I, S, B, U, J = Value

  /** The immediate format of an instruction format (R-type has no immediate). */
  def of(format: Format): Option[Type] = format match {
    case Format.R => None
    case Format.I => Some(I)
    case Format.S => Some(S)
    case Format.B => Some(B)
    case Format.U => Some(U)
    case Format.J => Some(J)
  }
}

/** The immediate generator: extracts the immediate of `inst` for its format, sign-extended to 32
  * bits (U-type: the upper 20 bits, the low 12 zero). The sign is always instruction bit 31, so
  * every format shares it; only the order of the other bits differs.
  */
class ImmGen extends Module {
  val io = IO(new Bundle {
    val inst   = Input(UInt(32.W))
    val format = Input(ImmFormat())
    val imm    = Output(UInt(32.W))
  })

  val i    = io.inst
  val sign = i(31)
  io.imm := MuxLookup(io.format, 0.U)(
    Seq(
      ImmFormat.I -> Cat(Fill(21, sign), i(30, 20)),
      ImmFormat.S -> Cat(Fill(21, sign), i(30, 25), i(11, 7)),
      ImmFormat.B -> Cat(Fill(20, sign), i(7), i(30, 25), i(11, 8), 0.U(1.W)),
      ImmFormat.U -> Cat(i(31, 12), 0.U(12.W)),
      ImmFormat.J -> Cat(Fill(12, sign), i(19, 12), i(20), i(30, 21), 0.U(1.W))
    )
  )
}
