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

  val shifted  = io.rdata >> (io.offset ## 0.U(3.W)) // offset * 8
  val unsigned = io.funct3(2)
  val byte     = shifted(7, 0)
  val half     = shifted(15, 0)
  io.value := MuxLookup(io.funct3(1, 0), shifted)(
    Seq(
      0.U -> Cat(Fill(24, byte(7) && !unsigned), byte),
      1.U -> Cat(Fill(16, half(15) && !unsigned), half)
    )
  )
}
