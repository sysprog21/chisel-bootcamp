package riscv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

class SingleCycleSpec extends AnyFlatSpec with ChiselSim {
  private def unsigned(x: Int) = BigInt(Integer.toUnsignedLong(x))

  "BranchUnit" should "decide branches like the golden model" in {
    simulate(new BranchUnit) { c =>
      val rnd     = new scala.util.Random(1)
      val corners = Seq(0, 1, -1, Int.MaxValue, Int.MinValue)
      def operand = if (rnd.nextInt(3) == 0) corners(rnd.nextInt(corners.size)) else rnd.nextInt()
      for (
        (f3, name) <- Seq(0 -> "beq", 1 -> "bne", 4 -> "blt", 5 -> "bge", 6 -> "bltu", 7 -> "bgeu"); _ <- 0 until 300
      ) {
        val (a, b) = if (rnd.nextInt(5) == 0) { val x = operand; (x, x) }
        else (operand, operand)
        c.io.funct3.poke(f3.U)
        c.io.a.poke(unsigned(a).U)
        c.io.b.poke(unsigned(b).U)
        c.io.taken.expect(IsaSim.taken(name, a, b).B, s"$name $a, $b")
      }
    }
  }

  "LoadExtend" should "take bytes and half-words out of the word like the golden model" in {
    simulate(new LoadExtend) { c =>
      val rnd = new scala.util.Random(2)
      for (_ <- 0 until 500; (f3, name) <- Seq(0 -> "lb", 1 -> "lh", 2 -> "lw", 4 -> "lbu", 5 -> "lhu")) {
        val bytes  = IsaSim.width(name)
        val word   = rnd.nextInt()
        val offset = rnd.nextInt(4 / bytes) * bytes
        val raw    = (Integer.toUnsignedLong(word) >>> (8 * offset)) & ((1L << (8 * bytes)) - 1)
        c.io.funct3.poke(f3.U)
        c.io.offset.poke(offset.U)
        c.io.rdata.poke(unsigned(word).U)
        c.io.value.expect(unsigned(IsaSim.loadValue(name, raw)).U, f"$name offset $offset word 0x$word%08x")
      }
    }
  }

  // Directed programs, one feature each. They run one after the other in one simulation:
  // CoSim.load resets the core, but not the RAM or the registers, so each program writes the
  // registers and the memory it reads.
  val directed = Seq(
    "arithmetic, immediates and x0" -> """
      li    a0, 0x12345678       # lui + addi
      lui   a1, 0xfffff          # a negative upper immediate
      addi  a2, a1, -1
      slti  a3, a1, 0            # signed
      sltiu a4, a1, 0            # unsigned
      sub   a5, zero, a0
      sra   a6, a5, a3
      srai  a7, a5, 31
      xori  t0, a0, -1
      auipc t1, 0x10
      add   zero, a0, a0         # x0 stays 0
      addi  t2, zero, 0
      .word 0x0ff0008f           # fence with rd = x1: writes nothing
      ecall
    """,
    "branches and jumps" -> """
      li    s0, -1
      li    s1, 1
      blt   s0, s1, 1f          # taken (signed)
      ebreak
  1:  bltu  s0, s1, 2f          # not taken (unsigned)
      bgeu  s0, s1, 2f          # taken
      ebreak
  2:  bne   s0, s0, 3f          # not taken
      beq   s0, s0, 3f          # taken
      ebreak
  3:  jal   ra, 4f
      j     5f
  4:  jalr  t0, 0(ra)           # back after the jal
  5:  la    t1, 6f
      jalr  zero, 1(t1)         # bit 0 of the target is cleared
      ebreak
  6:  bne   zero, zero, 2       # not taken: the misaligned target does not matter
      la    t2, 7f
      jalr  t2, 0(t2)           # rd = rs1
      ebreak
  7:  ecall
    """,
    "loads and stores" -> """
      la    s0, d
      li    t0, 0x8081
      sh    t0, 2(s0)
      sb    t0, 1(s0)
      lb    a0, 1(s0)
      lbu   a1, 1(s0)
      lh    a2, 2(s0)
      lhu   a3, 2(s0)
      lw    a4, 0(s0)
      sw    a4, 4(s0)
      lw    a5, 4(s0)
      lw    zero, 0(s0)         # a load into x0 still reads
      addi  s1, s0, 8
      lw    s1, -8(s1)          # a load into its own base register, with a negative offset
      ecall
      .data
  d:  .word 0x11223344, 0
    """,
    // the halts: each instruction that halts changes nothing
    "ebreak"                 -> "ebreak",
    "illegal (mret)"         -> ".word 0x30200073",
    "illegal (csrrw)"        -> ".word 0x00001073",
    "misaligned load"        -> "la t0, x\nlw a0, 1(t0)\nx: nop",
    "misaligned store"       -> "la t0, x\nsh a0, 1(t0)\nx: nop",
    "misaligned and outside" -> "li t0, 4\nlw a0, 2(t0)",
    "misaligned branch"      -> "beq zero, zero, 2",
    "misaligned jal"         -> "jal ra, 2",
    "misaligned jalr"        -> "la ra, x\njalr ra, 2(ra)\nx: nop",
    "load outside the RAM"   -> "li t0, 4\nlw a0, 0(t0)",
    "store outside the RAM"  -> "li t0, 4\nsw t0, 0(t0)",
    "fetch outside the RAM"  -> "lui t0, 0x10\njr t0"
  )

  def program(name: String): String = Programs.source("programs/src", name)

  "SingleCycleCore" should "retire directed programs, every halt, and the committed programs like the golden model" in {
    simulate(new SingleCycleSystem) { c =>
      for ((name, source) <- directed)
        CoSim.compare(c, Asm(source), maxCycles = 1000).foreach(d => fail(s"$name: $d"))
      for ((name, result) <- Seq("fib" -> 55, "fact" -> 120)) {
        CoSim.compare(c, Asm(program(name))).foreach(d => fail(s"$name: $d"))
        assert(CoSim.reg(c, riscv.Reg("a0")) == result, name) // the core has halted; a0 holds the result
      }
    }
  }

  it should "retire random programs like the golden model" in {
    simulate(new SingleCycleSystem) { c =>
      for (seed <- 1L to 20L)
        CoSim.compare(c, Asm(RandomProgram(seed))).foreach(d => fail(s"RandomProgram($seed): $d"))
    }
  }
}
