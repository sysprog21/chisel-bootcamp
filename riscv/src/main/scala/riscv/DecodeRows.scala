package riscv

/** The decode table: the control signals of each RV32I instruction (see `Row` and `Control` in
  * Decoder.scala). The datapath derives the rest from the kind: the register write, its source,
  * the memory access size (funct3) and the branch condition (funct3, evaluated by a comparator of
  * its own, as the ALU computes the branch target).
  */
object DecodeRows {
  import AluOp._
  import ImmFormat.{B, I, J, S, U}

  // the common shapes of rows
  private def reg(op: AluOp.Type) = Row(Kind.Alu, Some(op), None, Some(ASel.Rs1), Some(BSel.Rs2))    // add rd, rs1, rs2
  private def imm(op: AluOp.Type) = Row(Kind.Alu, Some(op), Some(I), Some(ASel.Rs1), Some(BSel.Imm)) // addi
  // the ALU computes the address (register + offset) or the target (pc + offset)
  private val load   = Row(Kind.Load, Some(Add), Some(I), Some(ASel.Rs1), Some(BSel.Imm))
  private val store  = Row(Kind.Store, Some(Add), Some(S), Some(ASel.Rs1), Some(BSel.Imm))
  private val branch = Row(Kind.Branch, Some(Add), Some(B), Some(ASel.Pc), Some(BSel.Imm))

  // a def, not a val: an unfinished row fails each time the table is used, not once for good
  def table: Map[String, Row] = Map(
    "LUI"   -> Row(Kind.Alu, Some(Add), Some(U), Some(ASel.Zero), Some(BSel.Imm)), // 0 + imm
    "AUIPC" -> Row(Kind.Alu, Some(Add), Some(U), Some(ASel.Pc), Some(BSel.Imm)),   // pc + imm
    "JAL"   -> Row(Kind.Jal, Some(Add), Some(J), Some(ASel.Pc), Some(BSel.Imm)),   // target pc + imm
    "JALR"  -> Row(Kind.Jalr, Some(Add), Some(I), Some(ASel.Rs1), Some(BSel.Imm)), // target rs1 + imm
    "BEQ"   -> branch,
    "BNE"   -> branch,
    "BLT"   -> branch,
    "BGE"   -> branch,
    "BLTU"  -> branch,
    "BGEU"  -> ??? /* Exercise */,
    "LB"    -> load,
    "LH"    -> load,
    "LW"    -> load,
    "LBU"   -> load,
    "LHU"   -> load,
    "SB"    -> store,
    "SH"    -> store,
    "SW"    -> store,
    "ADDI"  -> imm(Add),
    "SLTI"  -> imm(Slt),
    "SLTIU" -> ??? /* Exercise */,
    "XORI"  -> imm(Xor),
    "ORI"   -> imm(Or),
    "ANDI"  -> imm(And),
    // shifts: the immediate's upper bits hold funct7, but the ALU uses only its low 5 bits
    "SLLI"   -> imm(Sll),
    "SRLI"   -> imm(Srl),
    "SRAI"   -> imm(Sra),
    "ADD"    -> reg(Add),
    "SUB"    -> reg(Sub),
    "SLL"    -> reg(Sll),
    "SLT"    -> reg(Slt),
    "SLTU"   -> reg(Sltu),
    "XOR"    -> reg(Xor),
    "SRL"    -> reg(Srl),
    "SRA"    -> ??? /* Exercise */,
    "OR"     -> reg(Or),
    "AND"    -> reg(And),
    "FENCE"  -> Row(Kind.Fence), // a no-op here: nothing else matters
    "ECALL"  -> Row(Kind.Ecall),
    "EBREAK" -> Row(Kind.Ebreak)
  )
}
