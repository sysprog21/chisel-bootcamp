package riscv

import chisel3._
import chisel3.util._

/** The decoder written by hand, with `switch` on the opcode, as a comparison for the table-driven
  * [[Decoder]]: Module 5.3 checks that the two agree (on random instructions, in the fields that
  * matter for each), and Module 5.10 compares their sizes after synthesis.
  */
class SwitchDecoder extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))
    val ctrl = Output(new Control)
  })
  val inst             = io.inst
  val (opcode, f3, f7) = (inst(6, 0), inst(14, 12), inst(31, 25))
  val c                = io.ctrl
  c.legal     := false.B
  c.kind      := Kind.Alu
  c.aluOp     := AluOp.Add
  c.immFormat := ImmFormat.I
  c.aSel      := ASel.Rs1
  c.bSel      := BSel.Imm
  // the ALU operation that funct3 selects (funct7 bit 5 turns add into sub and srl into sra)
  val byF3 = VecInit(AluOp.Add, AluOp.Sll, AluOp.Slt, AluOp.Sltu, AluOp.Xor, AluOp.Srl, AluOp.Or, AluOp.And)(f3)
  switch(opcode) {
    is("b0110111".U) { c.legal := true.B; c.immFormat := ImmFormat.U; c.aSel := ASel.Zero } // lui
    is("b0010111".U) { c.legal := true.B; c.immFormat := ImmFormat.U; c.aSel := ASel.Pc } // auipc
    is("b1101111".U) { c.legal := true.B; c.kind := Kind.Jal; c.immFormat := ImmFormat.J; c.aSel := ASel.Pc }
    is("b1100111".U) { c.legal := f3 === 0.U; c.kind := Kind.Jalr }
    is("b1100011".U) {
      c.legal     := f3 =/= 2.U && f3 =/= 3.U
      c.kind      := Kind.Branch
      c.immFormat := ImmFormat.B
      c.aSel      := ASel.Pc
    }
    is("b0000011".U) { c.legal := f3 =/= 3.U && f3 < 6.U; c.kind := Kind.Load }
    is("b0100011".U) { c.legal := f3 < 3.U; c.kind := Kind.Store; c.immFormat := ImmFormat.S }
    is("b0010011".U) { // addi ... srai
      val shift = f3 === 1.U || f3 === 5.U
      c.legal := !shift || f7 === 0.U || (f3 === 5.U && f7 === "b0100000".U)
      c.aluOp := Mux(f3 === 5.U && f7(5), AluOp.Sra, byF3)
    }
    is("b0110011".U) { // add ... and
      c.legal := f7 === 0.U || (f7 === "b0100000".U && (f3 === 0.U || f3 === 5.U))
      c.aluOp := Mux(f7(5), Mux(f3 === 0.U, AluOp.Sub, AluOp.Sra), byF3)
      c.bSel  := BSel.Rs2
    }
    is("b0001111".U) { c.legal := f3 === 0.U; c.kind := Kind.Fence }
    is("b1110011".U) {
      c.legal := inst === "h00000073".U || inst === "h00100073".U
      c.kind  := Mux(inst(20), Kind.Ebreak, Kind.Ecall)
    }
  }
}
