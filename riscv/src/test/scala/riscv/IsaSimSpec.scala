package riscv

import org.scalatest.flatspec.AnyFlatSpec

class IsaSimSpec extends AnyFlatSpec {

  def program(name: String): Program =
    Asm.assemble(TestPrograms.source("programs/src", name)) // AsmSpec: equals hex/

  /** Runs `source` and returns the simulator, checking that it ends with ECALL. */
  def run(source: String): IsaSim = {
    val (sim, trace) = IsaSim.run(source, maxSteps = 10000)
    assert(sim.halted.contains(Halt.Ecall), trace.lastOption)
    sim
  }

  "IsaSim" should "run the committed programs" in {
    val fib = new IsaSim(program("fib"))
    fib.run()
    assert(fib.halted.contains(Halt.Ecall) && fib.reg(Reg("a0")) == 55)

    val factProgram = program("fact")
    val fact        = new IsaSim(factProgram)
    val trace       = fact.run()
    assert(fact.halted.contains(Halt.Ecall) && fact.reg(Reg("a0")) == 120)
    val result = factProgram.symbols("result")
    assert(fact.read(result, 4) == 120)
    assert(trace.last.halt.contains(Halt.Ecall) && trace.exists(_.mem.exists(m => m.write && m.addr == result)))
  }

  it should "compute like RV32I" in {
    val sim = run("""
      li    a0, -8
      srai  a1, a0, 1        # -4
      srli  a2, a0, 28       # 0xf
      sltu  a3, a2, a0       # 1: 15 < 0xfffffff8 unsigned
      slt   a4, a2, a0       # 0
      sltiu a5, zero, 1      # 1
      lui   a6, 0x80000
      addi  a6, a6, -1       # 0x7fffffff
      addi  a6, a6, 1        # wraps to 0x80000000
      auipc a7, 0            # its own address
      li    t0, 33
      sll   t1, a5, t0       # shifts use the low 5 bits: 1 << 1
      add   zero, a5, a5     # x0 stays 0
      ecall
    """)
    val r = (n: String) => sim.reg(Reg(n))
    assert(r("a1") == 0xfffffffcL && r("a2") == 0xf && r("a3") == 1 && r("a4") == 0 && r("a5") == 1)
    assert(r("a6") == 0x80000000L && r("a7") == 0x80000000L + 4 * 9 && r("t1") == 2 && r("zero") == 0)
  }

  it should "compare, subtract and shift negative numbers like RV32I" in {
    val sim = run("""
      li    s0, -1
      li    s1, 1
      li    a0, 0
      blt   s0, s1, 1f       # taken: -1 < 1
      ori   a0, a0, 1
  1:  bltu  s0, s1, 2f       # not taken: 0xffffffff > 1
      ori   a0, a0, 2
  2:  bge   s1, s0, 3f       # taken
      ori   a0, a0, 4
  3:  bgeu  s1, s0, 4f       # not taken
      ori   a0, a0, 8
  4:  beq   s0, s0, 5f       # taken
      ori   a0, a0, 16
  5:  bne   s0, s0, 6f       # not taken
      ori   a0, a0, 32
  6:  sub   a1, s1, s0       # 2
      li    t0, 4
      sra   a2, s0, t0       # -1
      srl   a3, s0, t0       # 0x0fffffff
      ecall
    """)
    assert(sim.reg(Reg("a0")) == (2 | 8 | 32) && sim.reg(Reg("a1")) == 2)
    assert(sim.reg(Reg("a2")) == 0xffffffffL && sim.reg(Reg("a3")) == 0x0fffffffL)
  }

  it should "load and store bytes, halves and words, little-endian" in {
    val sim = run("""
      la    s0, d
      li    t0, 0x8081
      sh    t0, 0(s0)
      lb    a0, 0(s0)        # 0x81 sign-extended
      lbu   a1, 1(s0)        # 0x80
      lh    a2, 0(s0)        # 0xffff8081
      lhu   a3, 0(s0)
      sb    t0, 3(s0)
      lw    a4, 0(s0)        # 0x81008081
      ecall
      .data
    d: .word 0
    """)
    val r = (n: String) => sim.reg(Reg(n))
    assert(r("a0") == 0xffffff81L && r("a1") == 0x80 && r("a2") == 0xffff8081L && r("a3") == 0x8081)
    assert(r("a4") == 0x81008081L)
  }

  it should "jump and link, and report each retired instruction" in {
    val (sim, trace) = IsaSim.run("""
          jal   f            # 0x80000000
          ecall              # 0x80000004
      f:  addi  a0, zero, 7  # 0x80000008
          jalr  t0, 0(ra)    # back to 0x80000004, t0 = 0x80000010
    """)
    assert(sim.halted.contains(Halt.Ecall) && sim.reg(Reg("t0")) == 0x80000010L)
    assert(trace.map(_.pc) == Seq(0x80000000L, 0x80000008L, 0x8000000cL, 0x80000004L))
    assert(trace.head == Retire(0x80000000L, Asm("jal 8").head, 0x80000008L, rd = 1, rdValue = 0x80000004L))
    assert(trace(1).rd == Reg("a0") && trace(1).rdValue == 7)
  }

  it should "halt on ebreak, illegal instructions, misalignment and bad addresses, changing nothing" in {
    val cases = Seq(
      "ebreak"                           -> Halt.Ebreak,
      ".word 0x30200073"                 -> Halt.Illegal,    // mret: no CSRs
      ".word 0"                          -> Halt.Illegal,
      "li t0, 4\nlw a0, 0(t0)"           -> Halt.BadAddress, // address 4: no RAM there
      "la t0, x\nlw a0, 1(t0)\nx: nop"   -> Halt.Misaligned,
      "la t0, x\nsh a0, 1(t0)\nx: nop"   -> Halt.Misaligned,
      "la ra, x\njalr ra, 2(ra)\nx: nop" -> Halt.Misaligned,
      "lui t0, 0x10\njr t0"              -> Halt.BadAddress, // fetch from 0x10000
      "beq zero, zero, 2"                -> Halt.Misaligned, // a taken branch to pc + 2
      "li t0, -4\nlw a0, 8(t0)"          -> Halt.BadAddress, // 0xfffffffc + 8 wraps to 4
      "li t0, 0x80010000\nlw a0, -2(t0)" -> Halt.Misaligned  // misaligned first, though also outside
    )
    for ((source, why) <- cases) {
      val (sim, trace) = IsaSim.run(source, maxSteps = 10)
      assert(sim.halted.contains(why), source)
      val last = trace.last
      assert(last.halt.contains(why) && last.rd == 0 && last.mem.isEmpty && last.nextPc == last.pc, source)
      assert(sim.reg(Reg("a0")) == 0 && sim.pc == last.pc, source)
    }
    val (sim, _) = IsaSim.run("la ra, x\njalr ra, 2(ra)\nx: nop")
    assert(sim.reg(1) == sim.base + 12, "a jump that halts does not write its link register") // ra = x
  }

  it should "not halt on a branch that is not taken, odd jalr offsets, or the top word of the RAM" in {
    val sim = run("""
      bne   zero, zero, 2      # not taken: the misaligned target does not matter
      la    t0, 1f
      jalr  ra, 1(t0)          # bit 0 of the target is cleared
  1:  li    t1, 0x80010000
      li    t2, 42
      sw    t2, -4(t1)         # the last word of the RAM
      lw    a0, -4(t1)
      ecall
    """)
    assert(sim.reg(Reg("a0")) == 42)
    val (_, trace) = IsaSim.run("auipc t0, 0\nlw zero, 0(t0)\necall") // a load into x0 still reads
    assert(trace(1).rd == 0 && trace(1).rdValue == 0 && trace(1).mem.exists(m => !m.write && m.data == 0x297))
  }

  it should "wrap the pc at 2^32" in {
    val top   = new IsaSim(Seq.fill(1024)(0x00000013L), base = 0xfffff000L, size = 4096) // nops to the top
    val trace = top.run(1024)
    assert(trace.last.pc == 0xfffffffcL && trace.last.nextPc == 0 && top.pc == 0)
  }

  it should "keep only the bytes a load reads from a device" in {
    val ones = new Device { // a device whose reads set every bit
      val base                                      = 0x10000000L
      val size                                      = 4L
      def read(addr: Long, bytes: Int)              = 0xffffffffL
      def write(addr: Long, bytes: Int, data: Long) = ()
    }
    val sim = new IsaSim(Asm("li t0, 0x10000000\nlbu a0, 0(t0)\nlhu a1, 0(t0)\necall"), devices = Seq(ones))
    val ret = sim.run()
    assert(sim.reg(Reg("a0")) == 0xff && sim.reg(Reg("a1")) == 0xffff)
    assert(ret.flatMap(_.mem).map(_.data) == Seq(0xffL, 0xffffL))
  }

  it should "reject a RAM that is not whole words inside the 32-bit address space" in {
    for ((base, size) <- Seq(2L -> 1024, 0L -> 1022, 0xffff0000L -> 0x20000, -4L -> 1024))
      assertThrows[IllegalArgumentException](new IsaSim(Nil, base, size))
  }

  "RandomProgram" should "make programs that assemble, halt with ECALL, and repeat for a seed" in {
    for (seed <- 1L to 30L) {
      val source = RandomProgram(seed)
      assert(source == RandomProgram(seed))
      val (sim, trace) = IsaSim.run(source, maxSteps = 1000)
      assert(sim.halted.contains(Halt.Ecall), s"seed $seed: ${trace.lastOption}")
      assert(sim.reg(0) == 0)
      assert(trace.exists(_.mem.exists(_.write)) && trace.exists(_.mem.exists(!_.write)), s"seed $seed")
    }
  }
}
