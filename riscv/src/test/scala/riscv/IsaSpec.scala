package riscv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class IsaSpec extends AnyFlatSpec with ChiselSim {

  "Instructions.all" should "list the 40 RV32I instructions once each" in {
    assert(Instructions.all.size == 40)
    assert(Instructions.all.map(_._1).distinct.size == 40)
  }

  it should "have 32-bit patterns whose opcode is a major opcode of Opcode" in {
    val opcodes = Opcode.all.map(_.litValue).toSet
    for ((name, p) <- Instructions.all) {
      assert(p.getWidth == 32, name)
      assert((p.mask & 0x7f) == 0x7f && opcodes(p.value & 0x7f), name)
    }
  }

  it should "not contain two patterns that match the same instruction word" in {
    for (((n1, p1), i) <- Instructions.all.zipWithIndex; (n2, p2) <- Instructions.all.drop(i + 1))
      assert(!p1.overlap(p2), s"$n1 and $n2 overlap")
  }

  it should "decode known encodings" in {
    val examples = Seq(
      0x00500093L -> "ADDI", // addi x1, x0, 5
      0x002081b3L -> "ADD",  // add x3, x1, x2
      0x40208233L -> "SUB",  // sub x4, x1, x2
      0x00209093L -> "SLLI", // slli x1, x1, 2
      0x4020d093L -> "SRAI", // srai x1, x1, 2
      0x0020d093L -> "SRLI", // srli x1, x1, 2
      0x0040a103L -> "LW",   // lw x2, 4(x1)
      0x0020a223L -> "SW",   // sw x2, 4(x1)
      0x00208463L -> "BEQ",  // beq x1, x2, 8
      0x008000efL -> "JAL",  // jal x1, 8
      0x00000073L -> "ECALL"
    )
    for ((word, name) <- examples) {
      assert(Instructions.matching(word) == Seq(name), f"0x$word%08x")
    }
  }

  "Opcode.safe" should "recognise the major opcodes in hardware" in {
    class OpcodeCheck extends Module {
      val io = IO(new Bundle {
        val bits  = Input(UInt(7.W))
        val isOp  = Output(Bool())
        val valid = Output(Bool())
      })
      val (opcode, valid) = Opcode.safe(io.bits)
      io.isOp  := valid && opcode === Opcode.Op
      io.valid := valid
    }
    simulate(new OpcodeCheck) { c =>
      c.io.bits.poke("b0110011".U)
      c.io.isOp.expect(true.B)
      c.io.bits.poke("b0000000".U)
      c.io.valid.expect(false.B)
    }
  }
}
