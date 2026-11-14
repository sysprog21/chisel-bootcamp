package riscv

import chisel3._
import chisel3.util.BitPat
import chisel3.util.experimental.decode._

/** What kind of instruction it is: the datapath derives the rest from it (whether rd is written,
  * from where, whether memory is read or written, how the next pc is computed).
  */
object Kind extends ChiselEnum {
  val Alu, Load, Store, Branch, Jal, Jalr, Fence, Ecall, Ebreak = Value
}

/** The ALU's first operand: a register, the pc (`auipc`, jumps and branch targets), or 0 (`lui`). */
object ASel extends ChiselEnum {
  val Rs1, Pc, Zero = Value
}

/** The ALU's second operand: a register or the immediate. */
object BSel extends ChiselEnum {
  val Rs2, Imm = Value
}

/** The control signals of an instruction. When `legal` is false, the instruction is not RV32I and
  * the other fields mean nothing.
  */
class Control extends Bundle {
  val legal     = Bool()
  val kind      = Kind()
  val aluOp     = AluOp()
  val immFormat = ImmFormat()
  val aSel      = ASel()
  val bSel      = BSel()
}

/** A row of the decode table: the control signals of one instruction. `None` means the instruction
  * does not use that signal, so the decoder may give anything there (a don't care, which lets the
  * logic minimizer make the decoder smaller).
  */
case class Row(
    kind: Kind.Type,
    aluOp: Option[AluOp.Type] = None,
    imm: Option[ImmFormat.Type] = None,
    a: Option[ASel.Type] = None,
    b: Option[BSel.Type] = None
)

/** An RV32I instruction as an input pattern of the decode table. */
case class InstPattern(name: String, bitPat: BitPat) extends DecodePattern

/** The table-driven decoder: `DecodeTable` builds the truth table from `DecodeRows.table` (one row
  * per instruction of `Instructions.all`), and a logic minimizer (Quine-McCluskey, built into
  * Chisel) turns it into gates. Words that match no row are illegal.
  */
object Decoder {
  def patterns: Seq[InstPattern] = Instructions.all.map { case (name, bitPat) => InstPattern(name, bitPat) }

  private def row(op: InstPattern): Row =
    DecodeRows.table.getOrElse(op.name, throw new IllegalArgumentException(s"no row for ${op.name} in DecodeRows"))

  // One field of the decoder's output per control signal; a field gives, for each instruction, the
  // bit pattern of its value (BitPat(enum value), or don't care).
  object LegalField extends BoolDecodeField[InstPattern] {
    def name                      = "legal"
    def genTable(op: InstPattern) = y
    override def default: BitPat  = n // a word that matches no instruction is illegal
  }
  object KindField extends DecodeField[InstPattern, Kind.Type] {
    def name                      = "kind"
    def chiselType                = Kind()
    def genTable(op: InstPattern) = BitPat(row(op).kind)
  }
  // the other fields are like KindField, but a row may leave them out (None: don't care)
  class OptionField[T <: EnumType](val name: String, gen: => T, get: Row => Option[T])
      extends DecodeField[InstPattern, T] {
    def chiselType                = gen
    def genTable(op: InstPattern) = get(row(op)).map(BitPat(_)).getOrElse(dc)
  }
  object AluOpField extends OptionField[AluOp.Type]("aluOp", AluOp(), _.aluOp)
  object ImmField   extends OptionField[ImmFormat.Type]("immFormat", ImmFormat(), _.imm)
  object ASelField  extends OptionField[ASel.Type]("aSel", ASel(), _.a)
  object BSelField  extends OptionField[BSel.Type]("bSel", BSel(), _.b)

  def table = new DecodeTable(patterns, Seq(LegalField, KindField, AluOpField, ImmField, ASelField, BSelField))
}

/** The decoder as a module: an instruction in, its control signals out (combinational). */
class Decoder extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))
    val ctrl = Output(new Control)
  })

  val table = Decoder.table
  // decoder.qmc: Chisel's own minimizer (DecodeTable.decode would try the external espresso first).
  // The cast to the enum fields is suppressed on purpose: a field may hold bits that are no value of
  // its enum wherever the table does not care, that is for an illegal word (then `legal` is false)
  // and for a field that the instruction's row leaves out (`None`, e.g. `aluOp` of `fence`), so the
  // datapath must not use a field that the instruction's kind does not need.
  val out = suppressEnumCastWarning(decoder.qmc(io.inst, table.table).asTypeOf(table.bundle))
  io.ctrl.legal     := out(Decoder.LegalField)
  io.ctrl.kind      := out(Decoder.KindField)
  io.ctrl.aluOp     := out(Decoder.AluOpField)
  io.ctrl.immFormat := out(Decoder.ImmField)
  io.ctrl.aSel      := out(Decoder.ASelField)
  io.ctrl.bSel      := out(Decoder.BSelField)
}
