package riscv

import org.scalatest.flatspec.AnyFlatSpec

import java.io.File
import scala.io.Source
import scala.util.{Random, Using}

/** Checks the software encoder, decoder and assembler against the hardware patterns of Isa.scala and
  * against GNU as (riscv-none-elf-as 2.44 -march=rv32i -mno-relax, which produced the expected words
  * below and the committed programs/hex files).
  */
class AsmSpec extends AnyFlatSpec {
  import Format._

  val rnd = new Random(1)

  /** A random instruction `name` whose fields are all in range. */
  def random(name: String): Inst = {
    def reg             = rnd.nextInt(32)
    def even(bits: Int) = (rnd.nextInt(1 << bits) - (1 << (bits - 1))) & ~1
    def set             = 1 + rnd.nextInt(15) // a nonempty subset of iorw
    name match {
      case "ecall" | "ebreak"          => Inst(name)
      case "fence"                     => Inst(name, imm = set << 4 | set) // what `fence pred, succ` can say
      case _ if Rv32i.isShiftImm(name) => Inst(name, reg, reg, imm = rnd.nextInt(32))
      case _                           =>
        Rv32i.encodings(name).format match {
          case R => Inst(name, reg, reg, reg)
          case I => Inst(name, reg, reg, imm = rnd.nextInt(4096) - 2048)
          case S => Inst(name, rs1 = reg, rs2 = reg, imm = rnd.nextInt(4096) - 2048)
          case B => Inst(name, rs1 = reg, rs2 = reg, imm = even(13))
          case U => Inst(name, reg, imm = rnd.nextInt() & 0xfffff000)
          case J => Inst(name, reg, imm = even(21))
        }
    }
  }

  /** `i` in assembly (branch and jump targets as offsets, which Asm reads as such). */
  def text(i: Inst): String = {
    def x(n: Int)       = s"x$n"
    def iorw(bits: Int) = "iorw".zipWithIndex.collect { case (c, k) if (bits & (8 >> k)) != 0 => c }.mkString
    i.name match {
      case "ecall" | "ebreak"                  => i.name
      case "fence"                             => s"fence ${iorw(i.imm >> 4)}, ${iorw(i.imm & 0xf)}"
      case n if Rv32i.isLoad(n) || n == "jalr" => s"$n ${x(i.rd)}, ${i.imm}(${x(i.rs1)})"
      case n                                   =>
        Rv32i.encodings(n).format match {
          case R => s"$n ${x(i.rd)}, ${x(i.rs1)}, ${x(i.rs2)}"
          case I => s"$n ${x(i.rd)}, ${x(i.rs1)}, ${i.imm}"
          case S => s"$n ${x(i.rs2)}, ${i.imm}(${x(i.rs1)})"
          case B => s"$n ${x(i.rs1)}, ${x(i.rs2)}, ${i.imm}"
          case U => s"$n ${x(i.rd)}, ${i.imm >>> 12}"
          case J => s"$n ${x(i.rd)}, ${i.imm}"
        }
    }
  }

  "Rv32i.encodings" should "have the 40 instructions of Instructions.all" in {
    assert(Rv32i.encodings.keySet == Instructions.all.map(_._1.toLowerCase).toSet)
  }

  "Every RV32I instruction" should "round-trip through the assembler, the BitPats and the decoder" in {
    for ((upper, pattern) <- Instructions.all; _ <- 1 to 100) {
      val inst = random(upper.toLowerCase)
      val word = Rv32i.encode(inst)
      assert(word >= 0 && word < (1L << 32), inst)
      assert(Asm(text(inst), base = 0) == Seq(word), text(inst))
      assert(Instructions.matching(word) == Seq(upper), f"$inst -> 0x$word%08x")
      assert(Rv32i.decode(word) == Some(inst), f"0x$word%08x")
    }
  }

  "Rv32i.decode" should "reject words that are not RV32I" in {
    val notRv32i = Seq(
      0x00000000L, // all zeros: an illegal instruction by design
      0xffffffffL,
      0x0000000bL,              // custom-0 opcode
      0x30200073L,              // mret (privileged)
      0x34011073L,              // csrrw (Zicsr)
      0x00000033L | (1L << 25), // mul (M extension)
      0x00007003L,              // load with funct3 = 7
      0x40001013L,              // slli with funct7 = 0x20
      0x00200073L               // SYSTEM opcode, funct3 = 0, but neither ECALL nor EBREAK
    )
    for (word <- notRv32i) assert(Rv32i.decode(word) == None, f"0x$word%08x")
  }

  "Rv32i.encode" should "reject fields out of range" in {
    val bad = Seq(
      Inst("addi", imm = 2048),
      Inst("addi", imm = -2049),
      Inst("slli", imm = 32),
      Inst("srai", imm = -1),
      Inst("beq", imm = 3),
      Inst("beq", imm = 4096),
      Inst("beq", imm = -4098),
      Inst("jal", imm = 1 << 20),
      Inst("jal", imm = -(1 << 20) - 2),
      Inst("lui", imm = 1),
      Inst("add", rd = 32),
      Inst("ecall", rd = 1)
    )
    for (inst <- bad) assertThrows[IllegalArgumentException](Rv32i.encode(inst))
  }

  "Asm" should "assemble instructions like GNU as" in {
    val source   = """
      back: lw    a0, -4(sp)
            sw    a1, 8(s0)
            lbu   t0, 0(a0)
            lh    t1, -2048(a1)
            sb    t2, 2047(a2)
            jalr  ra, 0(t1)
            ret
            jal   fn
            lui   a0, 0x12345
            auipc t0, 1
            srai  a0, a0, 31
            slli  a1, a1, 1
            sltiu a2, a3, -1
            xori  a4, a5, 0x7ff
            bltu  a0, a1, back
            bgeu  x31, zero, fn
            sra   s11, t6, fp
            fence
            ebreak
      fn:   ecall
    """
    val expected = Seq(0xffc12503L, 0x00b42423L, 0x00054283L, 0x80059303L, 0x7e760fa3L, 0x000300e7L, 0x00008067L,
      0x030000efL, 0x12345537L, 0x00001297L, 0x41f55513L, 0x00159593L, 0xfff6b613L, 0x7ff7c713L, 0xfcb564e3L,
      0x000ff863L, 0x408fddb3L, 0x0ff0000fL, 0x00100073L, 0x00000073L)
    assert(Asm(source, base = 0) == expected)
  }

  it should "assemble directives, sections and pseudo-instructions like GNU as" in {
    val source   = """
          .equ  N, 10
          .globl _start
          .text
      _start:
          li    a0, 0x12345678
          li    a1, -1
          li    a2, 0x800
          li    a3, 0x7ffff800
          li    a4, 0x80000000
          li    a5, N
          li    a6, 0x1000
          la    s0, table
          lw    t0, table
          sw    t0, value, t1
          lbu   t2, msg
          call  fn
          tail  fn
          not   a0, a1
          neg   a0, a1
          seqz  a0, a1
          snez  a0, a1
          sltz  a0, a1
          sgtz  a0, a1
      1:  blez  a0, 1b
          bgez  a0, 1f
          bltz  a0, 1b
      1:  bgtz  a0, 1b
          bgt   a0, a1, fn
          ble   a0, a1, fn
          bgtu  a0, a1, fn
          bleu  a0, a1, fn
          jr    t0
          jalr  t0
          jalr  ra, t0, 4
          lw    a0, (sp)
          fence rw, w
          fence i, o
          lui   a0, %hi(table)
          addi  a0, a0, %lo(table)
      2:  auipc a1, %pcrel_hi(msg)
          addi  a1, a1, %pcrel_lo(2b)
          .align 2
      fn: ret
          .data
      table: .word 1, -2, fn, 'A'
      value: .half 0x1234, -1
      msg:   .string "hi\n"
             .byte 7
             .align 2
             .ascii "ab"
             .zero 3
      end:   .word end - table
    """
    val expected = Seq(0x12345537L, 0x67850513L, 0xfff00593L, 0x00001637L, 0x80060613L, 0x800006b7L, 0x80068693L,
      0x80000737L, 0x00a00793L, 0x00001837L, 0x00000417L, 0x09440413L, 0x00000297L, 0x08c2a283L, 0x00000317L,
      0x08532a23L, 0x00000397L, 0x0903c383L, 0x00000097L, 0x070080e7L, 0x00000317L, 0x06830067L, 0xfff5c513L,
      0x40b00533L, 0x0015b513L, 0x00b03533L, 0x0005a533L, 0x00b02533L, 0x00a05063L, 0x00055463L, 0xfe054ce3L,
      0x00a04063L, 0x02a5cc63L, 0x02a5da63L, 0x02a5e863L, 0x02a5f663L, 0x00028067L, 0x000280e7L, 0x004280e7L,
      0x00012503L, 0x0310000fL, 0x0840000fL, 0x80000537L, 0x0bc50513L, 0x00000597L, 0x02058593L, 0x00008067L,
      0x00000001L, 0xfffffffeL, 0x800000b8L, 0x00000041L, 0xffff1234L, 0x000a6968L, 0x00000007L, 0x00006261L,
      0x00002100L, 0x00000000L)
    val program = Asm.assemble(source)
    assert(program.words == expected)
    assert(program.symbols("fn") == 0x800000b8L && program.symbols("table") == 0x800000bcL)
    assert(program.symbols("end") == 0x800000ddL)
  }

  it should "pad .text with nops" in {
    assert(Asm("nop\n.align 3\nebreak", base = 0) == Seq(0x13L, 0x13L, 0x00100073L))
    assert(Asm.assemble("nop\n.data\n.byte 1, 2").hex == "00000013\n00000201\n")
  }

  it should "align .data and .bss to their largest .align" in {
    val program = Asm.assemble("nop\n.data\n.byte 1\n.align 3\nx: .word 2\n.bss\n.align 4\ny: .zero 4", base = 0)
    assert(program.words == Seq(0x13L, 0L, 1L, 0L, 2L))
    assert(program.symbols("x") == 16 && program.symbols("y") == 32)
  }

  it should "read octal and hex escapes like GNU as" in {
    assert(Asm(".data\n.ascii \"\\012\\x41\\0B\"", base = 0) == Seq(0x4200410aL))
    assert(Asm("li a0, '\\101'", base = 0) == Seq(0x04100513L))
  }

  it should "take a .equ defined below as it took it in the first pass" in {
    assert(Asm("li a0, N\n.equ N, 5", base = 0) == Seq(0x00000537L, 0x00550513L))
  }

  it should "assemble each program of programs/src into its committed programs/hex image" in {
    def names(dir: String, ext: String) =
      new File(dir).list().filter(_.endsWith(ext)).map(_.dropRight(ext.length)).toSet
    val programs = names("programs/src", ".S")
    assert(programs.nonEmpty && programs == names("programs/hex", ".hex"), "a .S without its .hex, or the other way")
    for (p <- programs) {
      val source = Using.resource(Source.fromFile(s"programs/src/$p.S"))(_.mkString)
      val hex    = Using.resource(Source.fromFile(s"programs/hex/$p.hex"))(_.mkString)
      assert(
        Asm.assemble(source).hex == hex,
        s"$p: run sbt \"runMain riscv.Asm programs/src/$p.S programs/hex/$p.hex\""
      )
    }
  }

  it should "report what it cannot assemble, with the line" in {
    val bad = Seq(
      "mul a0, a1, a2",   // not RV32I
      "addi a0, a1",      // missing operand
      "addi a0, 4(sp)",   // a memory operand on a non-load
      "add a0, a1, x32",  // no such register
      "ecall a0, a1, 5",  // ecall has no operands
      "lui a0, 0x100000", // more than 20 bits
      "lui a0, -1",
      "li a0, 0x100000000", // more than 32 bits
      "j nowhere",          // unknown label
      "j 1b",               // no numeric label
      "x: nop\nx: nop",     // label defined twice
      ".data\nnop",         // an instruction outside .text
      ".byte 1\nnop",       // an instruction not on a word
      ".bss\n.word 1",      // data in .bss
      ".frobnicate",        // unknown directive
      "addi a0, a0, %pcrel_lo(0)",
      ".word 1,,2",
      ".zero",                   // missing size
      ".zero 0x7fffffff",        // too large
      ".equ N",                  // missing value
      ".align",                  // missing alignment
      ".data\n.string \"a\\\"",  // unterminated string
      "addi a0, a0, 0xffffffff", // not -1
      "lw a0, 4(sp",             // unbalanced parentheses
      "li a0, 'a"                // unterminated character
    )
    for (source <- bad) {
      val e = intercept[IllegalArgumentException](Asm(source))
      assert(e.getMessage.startsWith("line "), e.getMessage)
    }
  }
}
