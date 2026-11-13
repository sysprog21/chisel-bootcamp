package riscv

import chisel3._
import chisel3.util.BitPat

/** RV32I encodings: the major opcodes (bits 6-0) and the instruction patterns.
  *
  * Pinned to "The RISC-V Instruction Set Manual, Volume I: Unprivileged Architecture", version
  * 20240411. Only RV32I: no CSR instructions (see DESIGN.md), so `System` holds only ECALL and EBREAK.
  */
object Opcode extends ChiselEnum {
  val Load    = Value("b0000011".U)
  val MiscMem = Value("b0001111".U)
  val OpImm   = Value("b0010011".U)
  val Auipc   = Value("b0010111".U)
  val Store   = Value("b0100011".U)
  val Op      = Value("b0110011".U)
  val Lui     = Value("b0110111".U)
  val Branch  = Value("b1100011".U)
  val Jalr    = Value("b1100111".U)
  val Jal     = Value("b1101111".U)
  val System  = Value("b1110011".U)
}

object Instructions {
  private def any(width: Int) = BitPat.dontCare(width)
  private def bits(s: String) = BitPat("b" + s)
  // funct7, funct3 and the opcode fixed, the registers free
  private def r(funct7: String, funct3: String, opcode: Opcode.Type) =
    bits(funct7) ## any(10) ## bits(funct3) ## any(5) ## BitPat(opcode)
  // funct3 and the opcode fixed, everything else (registers, immediates) free
  private def f3(funct3: String, opcode: Opcode.Type) = any(17) ## bits(funct3) ## any(5) ## BitPat(opcode)
  private def u(opcode: Opcode.Type)                  = any(25) ## BitPat(opcode)

  val LUI   = u(Opcode.Lui)
  val AUIPC = u(Opcode.Auipc)
  val JAL   = u(Opcode.Jal)
  val JALR  = f3("000", Opcode.Jalr)

  val BEQ  = f3("000", Opcode.Branch)
  val BNE  = f3("001", Opcode.Branch)
  val BLT  = f3("100", Opcode.Branch)
  val BGE  = f3("101", Opcode.Branch)
  val BLTU = f3("110", Opcode.Branch)
  val BGEU = f3("111", Opcode.Branch)

  val LB  = f3("000", Opcode.Load)
  val LH  = f3("001", Opcode.Load)
  val LW  = f3("010", Opcode.Load)
  val LBU = f3("100", Opcode.Load)
  val LHU = f3("101", Opcode.Load)
  val SB  = f3("000", Opcode.Store)
  val SH  = f3("001", Opcode.Store)
  val SW  = f3("010", Opcode.Store)

  val ADDI  = f3("000", Opcode.OpImm)
  val SLTI  = f3("010", Opcode.OpImm)
  val SLTIU = f3("011", Opcode.OpImm)
  val XORI  = f3("100", Opcode.OpImm)
  val ORI   = f3("110", Opcode.OpImm)
  val ANDI  = f3("111", Opcode.OpImm)
  val SLLI  = r("0000000", "001", Opcode.OpImm)
  val SRLI  = r("0000000", "101", Opcode.OpImm)
  val SRAI  = r("0100000", "101", Opcode.OpImm)

  val ADD  = r("0000000", "000", Opcode.Op)
  val SUB  = r("0100000", "000", Opcode.Op)
  val SLL  = r("0000000", "001", Opcode.Op)
  val SLT  = r("0000000", "010", Opcode.Op)
  val SLTU = r("0000000", "011", Opcode.Op)
  val XOR  = r("0000000", "100", Opcode.Op)
  val SRL  = r("0000000", "101", Opcode.Op)
  val SRA  = r("0100000", "101", Opcode.Op)
  val OR   = r("0000000", "110", Opcode.Op)
  val AND  = r("0000000", "111", Opcode.Op)

  val FENCE  = f3("000", Opcode.MiscMem)
  val ECALL  = BitPat("b00000000000000000000000001110011")
  val EBREAK = BitPat("b00000000000100000000000001110011")

  /** The names of the patterns of `all` that match `word`: exactly one for an RV32I instruction. */
  def matching(word: Long): Seq[String] = {
    val w = BitPat(word.U(32.W))
    all.collect { case (name, p) if p.cover(w) => name }
  }

  /** A random instance of instruction `name` (as in `all`): its pattern's fixed bits, and random
    * bits wherever the pattern does not care (registers, immediates).
    */
  def random(name: String, rnd: scala.util.Random): Long = {
    val pattern = all.find(_._1 == name).getOrElse(throw new IllegalArgumentException(s"no instruction $name"))._2
    (rnd.nextLong() & ~pattern.mask.toLong | pattern.value.toLong) & 0xffffffffL
  }

  /** All 40 RV32I instructions (FENCE.I belongs to Zifencei, not RV32I). */
  val all: Seq[(String, BitPat)] = Seq(
    "LUI"    -> LUI,
    "AUIPC"  -> AUIPC,
    "JAL"    -> JAL,
    "JALR"   -> JALR,
    "BEQ"    -> BEQ,
    "BNE"    -> BNE,
    "BLT"    -> BLT,
    "BGE"    -> BGE,
    "BLTU"   -> BLTU,
    "BGEU"   -> BGEU,
    "LB"     -> LB,
    "LH"     -> LH,
    "LW"     -> LW,
    "LBU"    -> LBU,
    "LHU"    -> LHU,
    "SB"     -> SB,
    "SH"     -> SH,
    "SW"     -> SW,
    "ADDI"   -> ADDI,
    "SLTI"   -> SLTI,
    "SLTIU"  -> SLTIU,
    "XORI"   -> XORI,
    "ORI"    -> ORI,
    "ANDI"   -> ANDI,
    "SLLI"   -> SLLI,
    "SRLI"   -> SRLI,
    "SRAI"   -> SRAI,
    "ADD"    -> ADD,
    "SUB"    -> SUB,
    "SLL"    -> SLL,
    "SLT"    -> SLT,
    "SLTU"   -> SLTU,
    "XOR"    -> XOR,
    "SRL"    -> SRL,
    "SRA"    -> SRA,
    "OR"     -> OR,
    "AND"    -> AND,
    "FENCE"  -> FENCE,
    "ECALL"  -> ECALL,
    "EBREAK" -> EBREAK
  )
}
