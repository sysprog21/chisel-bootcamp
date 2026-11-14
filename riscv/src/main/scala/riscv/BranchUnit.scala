package riscv

import chisel3._
import chisel3.util._

/** The branch comparator: whether a branch is taken, from its funct3 (`beq` 0, `bne` 1, `blt` 4,
  * `bge` 5, `bltu` 6, `bgeu` 7) and its two registers. `lt` and `ge` compare as signed numbers,
  * `ltu` and `geu` as unsigned ones.
  */
class BranchUnit extends Module {
  val io = IO(new Bundle {
    val funct3 = Input(UInt(3.W))
    val a      = Input(UInt(32.W))
    val b      = Input(UInt(32.W))
    val taken  = Output(Bool())
  })

  io.taken := ??? // Exercise: compare a and b as funct3 says
}
