package riscv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** The five-stage pipeline, on programs padded with 2 nops after every instruction (it handles no
  * data hazards), compared instruction by instruction with the golden model.
  */
class PipelinedSpec extends AnyFlatSpec with ChiselSim {
  val nops = 2

  "PipelineFlush" should "flush the wrong path after a jump, and everything younger after a halt" in {
    simulate(new PipelineFlush) { c =>
      for (redirect <- Seq(false, true); halt <- Seq(false, true)) {
        c.io.redirect.poke(redirect.B)
        c.io.halt.poke(halt.B)
        c.io.flushIfId.expect((redirect || halt).B)
        c.io.flushIdEx.expect((redirect || halt).B)
        c.io.flushExMem.expect(halt.B)
      }
    }
  }

  // one feature each, and every halt cause (the halting instruction comes after padded ones too)
  val programs = Seq(
    "arithmetic" -> """
      li    a0, 0x12345678
      lui   a1, 0xfffff
      sub   a2, zero, a0
      sra   a3, a2, a1
      sltu  a4, a2, a0
      add   zero, a0, a0
      ecall
    """,
    "branches and jumps" -> """
      li    s0, -1
      li    s1, 1
      blt   s0, s1, 1f
      ebreak
  1:  bltu  s0, s1, 2f
      bgeu  s0, s1, 2f
      ebreak
  2:  jal   ra, 3f
      j     4f
  3:  jalr  t0, 0(ra)
  4:  la    t1, 5f
      jalr  zero, 1(t1)
      ebreak
  5:  ecall
    """,
    "loads and stores" -> """
      la    s0, d
      li    t0, 0x8081
      sh    t0, 2(s0)
      sb    t0, 1(s0)
      lb    a0, 1(s0)
      lhu   a1, 2(s0)
      lw    a2, 0(s0)
      ecall
      .data
  d:  .word 0x11223344
    """,
    "ebreak"                -> "ebreak",
    "illegal"               -> ".word 0x30200073",
    "misaligned load"       -> "la t0, x\nlw a0, 1(t0)\nx: nop",
    "misaligned jalr"       -> "la ra, x\njalr ra, 2(ra)\nx: nop",
    "store outside the RAM" -> "li t0, 4\nsw t0, 0(t0)",
    "fetch outside the RAM" -> "lui t0, 0x10\njr t0",
    "stores after a halt"   -> "la t0, d\nebreak\nsw t0, 0(t0)\n.data\nd: .word 7" // must not store
  )

  "PipelinedCore" should "retire padded programs like the golden model" in {
    simulate(new PipelinedSystem) { c =>
      for ((name, source) <- programs)
        CoSim.compare(c, Asm.padded(source, nops), maxCycles = 2000).foreach(d => fail(s"$name: $d"))
      for ((name, result) <- Seq("fib" -> 55, "fact" -> 120)) {
        CoSim.compare(c, Asm.padded(TestPrograms.source("programs/src", name), nops)).foreach(d => fail(s"$name: $d"))
        assert(CoSim.reg(c, riscv.Reg("a0")) == result, name)
      }
      for (seed <- 1L to 10L)
        CoSim.compare(c, Asm.padded(RandomProgram(seed), nops)).foreach(d => fail(s"RandomProgram($seed): $d"))
    }
  }

  // Unpadded, with the dependencies spaced by hand: younger instructions with side effects really sit
  // in the pipeline when a jump redirects it or an instruction halts it
  val flushed = Seq(
    "a store in EX during a halt" -> "auipc t0, 0\nnop\nnop\nebreak\nsw t0, 0(t0)\nsw t0, 4(t0)",
    "stores on the wrong path"    -> "auipc t0, 0\nnop\nnop\nj 1f\nsw t0, 0(t0)\nsw t0, 4(t0)\n1: ecall"
  )

  it should "let no younger instruction take effect after a jump or a halt" in {
    simulate(new PipelinedSystem) { c =>
      for ((name, source) <- flushed) CoSim.compare(c, Asm(source)).foreach(d => fail(s"$name: $d"))
    }
  }

  it should "need the padding: without it, an instruction reads a register before it is written" in {
    simulate(new PipelinedSystem) { c =>
      val difference = CoSim.compare(c, Asm("li a0, 5\naddi a1, a0, 1\necall"))
      assert(difference.exists(_.startsWith("instruction 1:")), difference)
    }
  }
}
