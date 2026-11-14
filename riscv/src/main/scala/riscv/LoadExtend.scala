package riscv

import chisel3._
import chisel3.util._

/** Takes the loaded value out of the memory word: `rdata` is the whole word at the (word-aligned)
  * address, `offset` the byte address's low two bits, and `funct3` the load (`lb` 0, `lh` 1, `lw` 2,
  * `lbu` 4, `lhu` 5). The byte or half-word starts at byte `offset` of the word (little-endian), and
  * is sign-extended (`lb`, `lh`) or zero-extended (`lbu`, `lhu`) to 32 bits. The address is aligned:
  * a half-word starts at offset 0 or 2.
  */
class LoadExtend extends Module {
  val io = IO(new Bundle {
    val funct3 = Input(UInt(3.W))
    val offset = Input(UInt(2.W))
    val rdata  = Input(UInt(32.W))
    val value  = Output(UInt(32.W))
  })

  io.value := ??? // Exercise: shift the bytes down, then extend them as funct3 says
}
