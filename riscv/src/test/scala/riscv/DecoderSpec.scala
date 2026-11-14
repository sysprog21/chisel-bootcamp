package riscv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class DecoderSpec extends AnyFlatSpec with ChiselSim {

  /** The row an instruction must have, worked out from its opcode and name alone (Rv32i), not from
    * the table: an independent description to check the table against.
    */
  def expectedRow(name: String): Row = {
    val e   = Rv32i.encodings(name.toLowerCase)
    val alu = AluOp.all.find(AluOp.mnemonic(_) == name.toLowerCase.filterNot(_ == 'i')) // addi -> add
    def row(kind: Kind.Type, a: ASel.Type, b: BSel.Type, op: Option[AluOp.Type] = Some(AluOp.Add)) =
      Row(kind, op, ImmFormat.of(e.format), Some(a), Some(b))
    e.opcode match {
      case 0x37 => row(Kind.Alu, ASel.Zero, BSel.Imm) // lui: 0 + imm
      case 0x17 => row(Kind.Alu, ASel.Pc, BSel.Imm)   // auipc: pc + imm
      case 0x6f => row(Kind.Jal, ASel.Pc, BSel.Imm)   // the target
      case 0x67 => row(Kind.Jalr, ASel.Rs1, BSel.Imm)
      case 0x63 => row(Kind.Branch, ASel.Pc, BSel.Imm)
      case 0x03 => row(Kind.Load, ASel.Rs1, BSel.Imm) // the address
      case 0x23 => row(Kind.Store, ASel.Rs1, BSel.Imm)
      case 0x13 => row(Kind.Alu, ASel.Rs1, BSel.Imm, alu)
      case 0x33 => row(Kind.Alu, ASel.Rs1, BSel.Rs2, alu)
      case 0x0f => Row(Kind.Fence)
      case 0x73 => Row(if (name == "ECALL") Kind.Ecall else Kind.Ebreak)
    }
  }

  // a row as plain values, to compare rows (enum values compare by their encoding)
  def values(r: Row) =
    (r.kind.litValue, r.aluOp.map(_.litValue), r.imm.map(_.litValue), r.a.map(_.litValue), r.b.map(_.litValue))

  "DecodeRows.table" should "give every RV32I instruction the row its opcode implies" in {
    val table = DecodeRows.table
    assert(table.keySet == Instructions.all.map(_._1).toSet)
    for ((name, row) <- table) assert(values(row) == values(expectedRow(name)), name)
  }

  "Decoder" should "decode every RV32I instruction as its row says, and flag other words as illegal" in {
    val table = DecodeRows.table
    simulate(new Decoder) { c =>
      val rnd = new scala.util.Random(1)
      for ((name, _) <- Instructions.all; _ <- 0 until 50) {
        val word = Instructions.random(name, rnd)
        val row  = table(name)
        c.io.inst.poke(word.U)
        val what = f"$name 0x$word%08x"
        c.io.ctrl.legal.expect(true.B, what)
        c.io.ctrl.kind.expect(row.kind, what)
        row.aluOp.foreach(c.io.ctrl.aluOp.expect(_, what))
        row.imm.foreach(c.io.ctrl.immFormat.expect(_, what))
        row.a.foreach(c.io.ctrl.aSel.expect(_, what))
        row.b.foreach(c.io.ctrl.bSel.expect(_, what))
      }
      // words close to instructions (random words would hardly ever be), random words, and known
      // non-RV32I words, keeping those that the software decoder rejects
      val words = Instructions.all.flatMap { case (n, _) => Seq.fill(20)(Instructions.nearMiss(n, rnd)) } ++
        Seq.fill(5000)(rnd.nextLong() & 0xffffffffL) ++
        Seq(0x00000000L, 0xffffffffL, 0x30200073L, 0x34011073L, 0x02208033L, 0x0000100fL)
      val illegal = words.filter(Rv32i.decode(_).isEmpty)
      for (word <- illegal) {
        c.io.inst.poke(word.U)
        c.io.ctrl.legal.expect(false.B, f"0x$word%08x")
      }
      assert(illegal.size > 3000)
    }
  }
}
