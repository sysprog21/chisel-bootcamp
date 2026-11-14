package riscv

import scala.collection.mutable

/** RV32I in software: the instruction formats, the registers' ABI names, an encoder, a decoder and an
  * assembler. Plain Scala (no hardware): the notebooks and tests use it to build programs, so the
  * course needs no RISC-V compiler.
  */
object Reg {

  /** ABI names of x0..x31 */
  val names: IndexedSeq[String] = IndexedSeq("zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2", "s0", "s1") ++
    (0 to 7).map("a" + _) ++ (2 to 11).map("s" + _) ++ (3 to 6).map("t" + _)

  // every name of every register: x0..x31, the ABI names, and fp (another name of s0)
  private val numbers: Map[String, Int] = (names.zipWithIndex ++ (0 until 32).map(n => s"x$n" -> n)).toMap + ("fp" -> 8)

  def isReg(name: String): Boolean = numbers.contains(name)

  /** The number of a register given as `x5`, `t0` or `fp`. */
  def apply(name: String): Int =
    numbers.getOrElse(name, throw new IllegalArgumentException(s"unknown register '$name'"))
}

/** The six instruction formats; they differ in which fields exist and how the immediate is split. */
sealed trait Format
object Format {
  case object R extends Format // rd, rs1, rs2
  case object I extends Format // rd, rs1, imm[11:0]
  case object S extends Format // rs1, rs2, imm[11:0] (stores)
  case object B extends Format // rs1, rs2, imm[12:1] (branches)
  case object U extends Format // rd, imm[31:12]
  case object J extends Format // rd, imm[20:1] (jal)
}

/** A decoded instruction. Fields that the format does not have are 0; `imm` is the sign-extended
  * immediate (for U-type, the value with its 12 low zero bits: `lui a0, 1` has imm 0x1000).
  */
case class Inst(name: String, rd: Int = 0, rs1: Int = 0, rs2: Int = 0, imm: Int = 0)

object Rv32i {
  import Format._

  /** name -> (format, opcode, funct3, funct7). funct7 also tells SRAI from SRLI. */
  case class Encoding(format: Format, opcode: Int, funct3: Int = 0, funct7: Int = 0)

  val encodings: Map[String, Encoding] = Map(
    "lui"    -> Encoding(U, 0x37),
    "auipc"  -> Encoding(U, 0x17),
    "jal"    -> Encoding(J, 0x6f),
    "jalr"   -> Encoding(I, 0x67, 0),
    "beq"    -> Encoding(B, 0x63, 0),
    "bne"    -> Encoding(B, 0x63, 1),
    "blt"    -> Encoding(B, 0x63, 4),
    "bge"    -> Encoding(B, 0x63, 5),
    "bltu"   -> Encoding(B, 0x63, 6),
    "bgeu"   -> Encoding(B, 0x63, 7),
    "lb"     -> Encoding(I, 0x03, 0),
    "lh"     -> Encoding(I, 0x03, 1),
    "lw"     -> Encoding(I, 0x03, 2),
    "lbu"    -> Encoding(I, 0x03, 4),
    "lhu"    -> Encoding(I, 0x03, 5),
    "sb"     -> Encoding(S, 0x23, 0),
    "sh"     -> Encoding(S, 0x23, 1),
    "sw"     -> Encoding(S, 0x23, 2),
    "addi"   -> Encoding(I, 0x13, 0),
    "slti"   -> Encoding(I, 0x13, 2),
    "sltiu"  -> Encoding(I, 0x13, 3),
    "xori"   -> Encoding(I, 0x13, 4),
    "ori"    -> Encoding(I, 0x13, 6),
    "andi"   -> Encoding(I, 0x13, 7),
    "slli"   -> Encoding(I, 0x13, 1, 0x00),
    "srli"   -> Encoding(I, 0x13, 5, 0x00),
    "srai"   -> Encoding(I, 0x13, 5, 0x20),
    "add"    -> Encoding(R, 0x33, 0, 0x00),
    "sub"    -> Encoding(R, 0x33, 0, 0x20),
    "sll"    -> Encoding(R, 0x33, 1, 0x00),
    "slt"    -> Encoding(R, 0x33, 2, 0x00),
    "sltu"   -> Encoding(R, 0x33, 3, 0x00),
    "xor"    -> Encoding(R, 0x33, 4, 0x00),
    "srl"    -> Encoding(R, 0x33, 5, 0x00),
    "sra"    -> Encoding(R, 0x33, 5, 0x20),
    "or"     -> Encoding(R, 0x33, 6, 0x00),
    "and"    -> Encoding(R, 0x33, 7, 0x00),
    "fence"  -> Encoding(I, 0x0f, 0),
    "ecall"  -> Encoding(I, 0x73, 0),
    "ebreak" -> Encoding(I, 0x73, 0)
  )

  def isShiftImm(name: String): Boolean = name == "slli" || name == "srli" || name == "srai"
  def isLoad(name: String): Boolean     = encodings.get(name).exists(_.opcode == 0x03)
  def isAluImm(name: String): Boolean   = encodings.get(name).exists(_.opcode == 0x13) // addi ... srai

  private def bits(x: Long, hi: Int, lo: Int): Int = ((x >>> lo) & ((1L << (hi - lo + 1)) - 1)).toInt
  def signExtend(x: Int, width: Int): Int          = (x << (32 - width)) >> (32 - width)

  /** The 32-bit word of `i` (as a non-negative Long). */
  def encode(i: Inst): Long = {
    val e = encodings.getOrElse(i.name, throw new IllegalArgumentException(s"not an RV32I instruction: ${i.name}"))
    def check(cond: Boolean, what: String) = require(cond, s"${i.name}: $what out of range: ${i.imm}")
    for (r <- Seq(i.rd, i.rs1, i.rs2)) require(r >= 0 && r < 32, s"${i.name}: there is no register x$r")
    if (i.name == "ecall" || i.name == "ebreak") require(i == Inst(i.name), s"${i.name} has no operands")
    val imm        = i.imm.toLong
    val word: Long = e.format match {
      case R => (e.funct7.toLong << 25) | (i.rs2 << 20) | (i.rs1 << 15) | (e.funct3 << 12) | (i.rd << 7)
      case I if isShiftImm(i.name) =>
        check(i.imm >= 0 && i.imm < 32, "shift amount")
        (e.funct7.toLong << 25) | (imm << 20) | (i.rs1 << 15) | (e.funct3 << 12) | (i.rd << 7)
      case I if i.name == "ebreak" => 1L << 20
      case I                       =>
        check(i.imm >= -2048 && i.imm < 2048, "immediate")
        ((imm & 0xfff) << 20) | (i.rs1 << 15) | (e.funct3 << 12) | (i.rd << 7)
      case S =>
        check(i.imm >= -2048 && i.imm < 2048, "offset")
        ((imm >> 5 & 0x7f) << 25) | (i.rs2 << 20) | (i.rs1 << 15) | (e.funct3 << 12) | ((imm & 0x1f) << 7)
      case B =>
        check(i.imm >= -4096 && i.imm < 4096 && i.imm % 2 == 0, "offset")
        ((imm >> 12 & 1) << 31) | ((imm >> 5 & 0x3f) << 25) | (i.rs2 << 20) | (i.rs1 << 15) |
          (e.funct3 << 12) | ((imm >> 1 & 0xf) << 8) | ((imm >> 11 & 1) << 7)
      case U =>
        check((i.imm & 0xfff) == 0, "immediate (low 12 bits must be 0)")
        (imm & 0xfffff000L) | (i.rd << 7)
      case J =>
        check(i.imm >= -(1 << 20) && i.imm < (1 << 20) && i.imm % 2 == 0, "offset")
        ((imm >> 20 & 1) << 31) | ((imm >> 1 & 0x3ff) << 21) | ((imm >> 11 & 1) << 20) |
          ((imm >> 12 & 0xff) << 12) | (i.rd << 7)
    }
    word | e.opcode
  }

  private val byOpcode = encodings.toSeq.groupBy(_._2.opcode)

  /** The instruction of a 32-bit word, or None if it is not an RV32I instruction. */
  def decode(word: Long): Option[Inst] = {
    val opcode     = bits(word, 6, 0)
    val rd         = bits(word, 11, 7)
    val funct3     = bits(word, 14, 12)
    val rs1        = bits(word, 19, 15)
    val rs2        = bits(word, 24, 20)
    val funct7     = bits(word, 31, 25)
    val candidates = byOpcode.getOrElse(opcode, Nil).filter { case (name, e) =>
      e.format match {
        case U | J                                  => true
        case _ if e.format == R || isShiftImm(name) => e.funct3 == funct3 && e.funct7 == funct7
        case _                                      => e.funct3 == funct3
      }
    }
    candidates.map(_._1).toList.sorted match {
      case List("ebreak", "ecall") => // the SYSTEM opcode: only these two exact words are RV32I
        if (word == 0x00000073L) Some(Inst("ecall"))
        else if (word == 0x00100073L) Some(Inst("ebreak"))
        else None
      case List(name) =>
        val e = encodings(name)
        Some(e.format match {
          case R                     => Inst(name, rd, rs1, rs2)
          case I if isShiftImm(name) => Inst(name, rd, rs1, imm = rs2)
          case I                     => Inst(name, rd, rs1, imm = signExtend(bits(word, 31, 20), 12))
          case S                     => Inst(name, rs1 = rs1, rs2 = rs2, imm = signExtend(funct7 << 5 | rd, 12))
          case B                     =>
            val imm =
              bits(word, 31, 31) << 12 | bits(word, 7, 7) << 11 | bits(word, 30, 25) << 5 | bits(word, 11, 8) << 1
            Inst(name, rs1 = rs1, rs2 = rs2, imm = signExtend(imm, 13))
          case U => Inst(name, rd, imm = (word & 0xfffff000L).toInt)
          case J =>
            val imm =
              bits(word, 31, 31) << 20 | bits(word, 19, 12) << 12 | bits(word, 20, 20) << 11 | bits(word, 30, 21) << 1
            Inst(name, rd, imm = signExtend(imm, 21))
        })
      case _ => None
    }
  }

  /** `word` as assembly, much as `objdump -d -M no-aliases` shows it (ABI register names, no
    * pseudo-instructions, ", " between operands, shift amounts in decimal): branch and jump targets
    * are absolute, computed from `pc`; `fence` shows no operands; a word that is not RV32I shows as
    * `.word`.
    */
  def disassemble(word: Long, pc: Long = 0): String = decode(word).fold(f".word 0x${word & 0xffffffffL}%08x") { i =>
    def r(n: Int) = Reg.names(n)
    def target    = f"0x${(pc + i.imm) & 0xffffffffL}%x"
    if (i.name == "fence" || i.name == "ecall" || i.name == "ebreak") i.name
    else
      encodings(i.name).format match {
        case R                                       => s"${i.name} ${r(i.rd)}, ${r(i.rs1)}, ${r(i.rs2)}"
        case I if i.name == "jalr" || isLoad(i.name) => s"${i.name} ${r(i.rd)}, ${i.imm}(${r(i.rs1)})"
        case I                                       => s"${i.name} ${r(i.rd)}, ${r(i.rs1)}, ${i.imm}"
        case S                                       => s"${i.name} ${r(i.rs2)}, ${i.imm}(${r(i.rs1)})"
        case B                                       => s"${i.name} ${r(i.rs1)}, ${r(i.rs2)}, $target"
        case U                                       => f"${i.name} ${r(i.rd)}, 0x${i.imm >>> 12}%x"
        case J                                       => s"${i.name} ${r(i.rd)}, $target"
      }
  }
}

/** An assembled program. `words` is the memory image from `base`, one little-endian 32-bit word per
  * element: the `.text` section, then the `.data` section (from the next word, or the next multiple of
  * its largest `.align`, zeros in between). `.bss` follows the
  * data; it is not in the image, and nothing clears it. `symbols` holds the address of every
  * label (except numeric ones).
  */
case class Program(base: Long, words: IndexedSeq[Long], symbols: Map[String, Long]) {

  /** The image as `$readmemh` reads it: one word per line, in hex. */
  def hex: String = words.map(w => f"$w%08x\n").mkString
}

/** An RV32I assembler in the GNU syntax, so that the course needs no RISC-V toolchain (like the
  * assembler built into the Ripes simulator). It reads
  *
  *   - statements one per line or separated by `;`, `#` comments, labels (`loop:`, alone or before
  *     a statement) and numeric local labels (`1:`, referred to as `1b` backwards or `1f` forwards);
  *   - the sections `.text`, `.data` and `.bss` (also `.section .text.init` and the like;
  *     `.rodata` goes to `.data`);
  *   - the directives `.word`/`.long`, `.half`/`.short`, `.byte`, `.ascii`, `.string`/`.asciz`,
  *     `.zero`/`.space`, `.fill`, `.align`/`.p2align` (a power of 2), `.balign`, `.equ`/`.set`,
  *     `.rept n` ... `.endr` (n a number), and `.globl`/`.global` and `.option` (nothing to do);
  *   - every RV32I instruction. An immediate is an expression of numbers (decimal, octal `010`,
  *     `0x`, `0b`, `'c'`; 64-bit, as in GNU as), symbols, parentheses, unary `-` and `~`, and the binary
  *     operators of GNU as with its precedence (`*`, `/`, `%`, `<<`, `>>` above `|`, `&`, `^` above
  *     `+`, `-`; `>>` is logical), and `%hi`, `%lo`, `%pcrel_hi` and `%pcrel_lo`. A register-register
  *     ALU instruction with an immediate is its immediate form (`sll a0, a0, 1` is `slli`);
  *   - the RV32I pseudo-instructions `nop`, `li` (any 32-bit value), `la` (and `lla`), `mv`, `not`,
  *     `neg`, `seqz`, `snez`, `sltz`, `sgtz`, `beqz`, `bnez`, `blez`, `bgez`, `bltz`, `bgtz`, `bgt`,
  *     `ble`, `bgtu`, `bleu`, `j`, `jal label`, `jr rs` (and `jr rs, offset`), `jalr rs`, `ret`,
  *     `call`, `tail`, and loads and stores of a symbol (`lw rd, symbol` and `sw rs, symbol, rt`,
  *     through `auipc`).
  *
  * It assembles the rv32ui tests of riscv-tests (riscv/programs/riscv-tests) exactly as GNU as does.
  * A branch or jump target that uses a symbol is an address; a plain number is an offset from the
  * instruction (`beq a0, a1, 8` skips one instruction). GNU as reads a plain number as an address
  * instead; the course's programs use labels, which mean the same in both. Execution starts at the
  * first word of `.text`, at `base`.
  */
object Asm {

  /** The memory image of `source` assembled for address `base` (see [[Program]]). */
  def apply(source: String, base: Long = Ram.base): Seq[Long] = assemble(source, base).words

  /** `source` assembled with `padding` nops after every instruction (after each of the instructions
    * of a pseudo-instruction too): a pipeline without hazard handling then needs nothing else. Labels
    * keep working; a branch or jump offset written as a number or relative to `.` (`j .+8`) does not
    * account for the nops, and an instruction written as data (`.word`) gets none after it.
    */
  def padded(source: String, padding: Int, base: Long = Ram.base): Seq[Long] = {
    require(padding >= 0, s"padding $padding")
    new Assembler(source, base, padding).program.words
  }

  def assemble(source: String, base: Long = Ram.base): Program = new Assembler(source, base).program

  /** `sbt "runMain riscv.Asm in.S out.hex"` writes the memory image of `in.S` for `$readmemh`. */
  def main(args: Array[String]): Unit = args match {
    case Array(in, out) =>
      val source = java.nio.file.Files.readString(java.nio.file.Paths.get(in))
      java.nio.file.Files.writeString(java.nio.file.Paths.get(out), assemble(source).hex)
    case _ => sys.error("usage: riscv.Asm <in.S> <out.hex>")
  }
}

/** The two passes of [[Asm]]: the first finds every statement's section and offset (so every
  * label's address), the second produces the bytes.
  */
private final class Assembler(source: String, base: Long, padding: Int = 0) {
  private def fail(msg: String): Nothing = throw new IllegalArgumentException(msg)

  // ---- lexing

  /** The statements of a line: GNU as separates them with `;`, and `#` starts a comment (both
    * outside strings and character literals).
    */
  private def statements(line: String): Seq[String] = {
    val out         = mutable.ListBuffer[String]()
    var quote: Char = 0
    var start       = 0
    var i           = 0
    while (i < line.length && !(quote == 0 && line(i) == '#')) {
      val c = line(i)
      if (quote != 0) { if (c == '\\') i += 1 else if (c == quote) quote = 0 }
      else if (c == '"' || c == '\'') quote = c
      else if (c == ';') { out += line.substring(start, i); start = i + 1 }
      i += 1
    }
    (out += line.substring(start, i.min(line.length))).toSeq
  }

  /** The operands, split at the commas that are outside strings, character literals and parentheses. */
  private def operands(s: String): List[String] = {
    val out         = mutable.ListBuffer[String]()
    val cur         = new StringBuilder
    var depth       = 0
    var quote: Char = 0
    var i           = 0
    while (i < s.length) {
      val c = s(i)
      if (quote != 0) {
        cur += c
        if (c == '\\' && i + 1 < s.length) { i += 1; cur += s(i) }
        else if (c == quote) quote = 0
      } else
        c match {
          case '"' | '\''        => quote = c; cur += c
          case '('               => depth += 1; cur += c
          case ')'               => depth -= 1; cur += c
          case ',' if depth == 0 => out += cur.toString.trim; cur.clear()
          case _                 => cur += c
        }
      i += 1
    }
    if (quote != 0) fail(s"missing closing $quote")
    if (depth != 0) fail("unbalanced parentheses")
    if (s.trim.nonEmpty) out += cur.toString.trim
    if (out.exists(_.isEmpty)) fail("empty operand")
    out.toList
  }

  private val labelDef = """\s*([A-Za-z_.$][\w.$]*|\d+)\s*:(.*)""".r
  private val memRef   = """(.*)\(\s*(\w+)\s*\)\s*""".r
  private val localRef = """(\d+)([bf])""".r

  private def unescape(c: Char): Char = c match {
    case 'n' => '\n'
    case 't' => '\t'
    case 'r' => '\r'
    case c   => c // \\, \", \'
  }

  /** The character of the escape sequence whose backslash is `s(i)`, and the index after it: `\n` and
    * the like, up to three octal digits (`\0`, `\012`) or `\x` and hex digits, as GNU as reads them.
    */
  private def escape(s: String, i: Int, end: Int): (Char, Int) = {
    val after = s.slice(i + 1, end)
    val octal = after.take(3).takeWhile(c => c >= '0' && c <= '7')
    if (octal.nonEmpty) ((Integer.parseInt(octal, 8) & 0xff).toChar, i + 1 + octal.length)
    else if (after.startsWith("x")) {
      val hex = after.drop(1).takeWhile(Character.digit(_, 16) >= 0)
      if (hex.isEmpty) fail(s"\\x without hex digits in $s")
      ((BigInt(hex, 16) & 0xff).toChar, i + 2 + hex.length)
    } else if (after.isEmpty) fail(s"nothing after \\ in $s")
    else (unescape(after.head), i + 2)
  }

  private def string(s: String): Array[Byte] = {
    if (s.length < 2 || s.head != '"' || s.last != '"') fail(s"not a string: $s")
    val out = mutable.ArrayBuffer[Byte]()
    var i   = 1
    while (i < s.length - 1) {
      if (s(i) == '\\') { val (c, next) = escape(s, i, s.length - 1); out += c.toByte; i = next }
      else { out ++= s(i).toString.getBytes("UTF-8"); i += 1 }
    }
    out.toArray
  }

  /** Runs `body` for one source line, adding the line to the message of any error. */
  private def at[T](line: Int, text: String)(body: => T): T =
    try body
    catch {
      case e: IllegalArgumentException => throw new IllegalArgumentException(s"line $line: ${e.getMessage}: $text", e)
    }

  // ---- pass 1: statements, their sections and offsets, and the labels

  private case class Stmt(
      line: Int,
      text: String,
      op: String,
      args: List[String],
      section: String,
      offset: Int,
      size: Int
  )

  /** A numeric label such as `1:`, defined just before statement number `seq`. */
  private case class Local(number: String, seq: Int, section: String, offset: Int)

  private val stmts   = mutable.ArrayBuffer[Stmt]()
  private val labels  = mutable.Map[String, (String, Int)]() // name -> (section, offset)
  private val locals  = mutable.ArrayBuffer[Local]()
  private val equs    = mutable.Map[String, Long]()
  private val equSeq  = mutable.Map[String, Int]()           // name -> the number of the first statement after its .equ
  private val sizes   = mutable.Map("text" -> 0, "data" -> 0, "bss" -> 0)
  private var section = "text"
  private var inPass1 = true                                 // labels have no address yet
  private val pcrelHi = mutable.Map[Long, Long]() // address of an auipc with %pcrel_hi -> the address it aims at

  private def defined(name: String) = labels.contains(name) || equs.contains(name)

  private def sectionOf(name: String): String = {
    def is(prefixes: String*) = prefixes.exists(p => name == p || name.startsWith(p + "."))
    if (is(".text")) "text"
    else if (is(".data", ".rodata", ".sdata", ".srodata")) "data"
    else if (is(".bss", ".sbss")) "bss"
    else fail(s"unknown section $name")
  }

  /** The value of an expression of numbers and `.equ` symbols defined above. */
  private def constant(expr: String): Long =
    evaluate(expr, 0, n => equs.getOrElse(n, fail(s"$n is not a constant defined above")))._1

  /** The alignment in bytes of `.align n`, `.p2align n` or `.balign n`. */
  private def alignment(op: String, arg: String): Long = {
    val a = if (op == ".balign") constant(arg) else 1L << constant(arg).min(13)
    if (a <= 0 || a > 4096 || (a & (a - 1)) != 0) fail(s"bad alignment $arg")
    a
  }

  /** The padding that an alignment directive adds at `offset`. */
  private def padding(op: String, arg: String, offset: Int): Int = {
    val a = alignment(op, arg)
    ((a - offset % a) % a).toInt
  }

  // the largest alignment of each section (at least a word), which its start address must have too
  private val sectionAlign = mutable.Map("text" -> 4L, "data" -> 4L, "bss" -> 4L)

  private val alignments = Set(".align", ".p2align", ".balign")
  private val maxSize    = 16L << 20 // per section: far beyond the course's memories, and no huge arrays

  /** The repeat count and the size of `.fill repeat[, size[, value]]` (size 1 by default). */
  private def fill(args: List[String]): (Long, Long) = args match {
    case r :: rest if rest.size <= 2 =>
      val (repeat, each) = (constant(r), rest.headOption.map(constant).getOrElse(1L))
      if (repeat < 0 || repeat > maxSize || each < 0 || each > 8 || repeat * each > maxSize)
        fail(s"bad .fill $repeat, $each")
      (repeat, each)
    case _ => fail(".fill needs a repeat count, and maybe a size and a value")
  }

  private def one(op: String, args: List[String]): String = args match {
    case List(a) => a
    case _       => fail(s"$op needs one operand")
  }

  private def size(op: String, args: List[String], offset: Int): Int = op match {
    case ".word" | ".long"    => 4 * args.size
    case ".half" | ".short"   => 2 * args.size
    case ".byte"              => args.size
    case ".ascii"             => args.map(string(_).length).sum
    case ".string" | ".asciz" => args.map(string(_).length + 1).sum
    case ".zero" | ".space" => val n = constant(one(op, args)); if (n < 0 || n > maxSize) fail(s"bad size $n"); n.toInt
    case ".fill"            => val (repeat, each) = fill(args); (repeat * each).toInt
    case a if alignments(a) =>
      sectionAlign(section) = sectionAlign(section).max(alignment(op, one(op, args)))
      padding(op, one(op, args), offset)
    case d if d.startsWith(".") => fail(s"unknown directive $d")
    case _ if section != "text" => fail(s"instructions belong in .text, not .$section")
    case _ if offset % 4 != 0 => fail("instruction not on a 4-byte boundary (add .align 2 before it)")
    case _ => 4 * instructions(op, args, 0, stmts.size).size
  }

  /** `(line, statement)` with each `.rept n` ... `.endr` block written out n times. The count must
    * be a number (`.equ` symbols are not known yet when blocks are written out).
    */
  private def unroll(stmts: List[(Int, String)]): List[(Int, String)] = {
    def directive(text: String)              = text.trim.takeWhile(!_.isWhitespace)
    val out                                  = mutable.ListBuffer[(Int, String)]()
    var work                                 = 0L // repetitions and statements: bounded, even for empty blocks
    def go(stmts: List[(Int, String)]): Unit = {
      var rest = stmts
      while (rest.nonEmpty) {
        val (line, text) = rest.head
        directive(text) match {
          case ".rept" =>
            val n = at(line, text)(constant(text.trim.stripPrefix(".rept").trim))
            if (n < 0 || n > maxSize) at(line, text)(fail(s"bad .rept count $n"))
            // the matching .endr: count the .rept and .endr in between, stopping there
            var depth = 1
            val body  = rest.tail.takeWhile { case (_, t) =>
              directive(t) match {
                case ".rept" => depth += 1
                case ".endr" => depth -= 1
                case _       =>
              }
              depth > 0
            }
            if (depth > 0) at(line, text)(fail(".rept without .endr"))
            for (_ <- 0L until n) {
              work += 1
              go(body)
              if (work > maxSize) at(line, text)(fail(".rept writes out too many statements"))
            }
            rest = rest.tail.drop(body.size + 1)
          case ".endr" => at(line, text)(fail(".endr without .rept"))
          case _       => out += rest.head; work += 1; rest = rest.tail
        }
      }
    }
    go(stmts)
    out.toList
  }

  /** The labels at the start of a statement, each as a statement of its own, then the rest (so that
    * `loop: .rept 2` and `1: .endr` are seen by `unroll`).
    */
  private def splitLabels(text: String): List[String] = text match {
    case labelDef(name, after) => s"$name:" :: splitLabels(after)
    case _                     => List(text)
  }

  private val lines: List[(Int, String)] = unroll(
    source.linesIterator.zipWithIndex.flatMap { case (raw, index) =>
      statements(raw).flatMap(splitLabels).map((index + 1, _))
    }.toList
  )

  for ((line, text) <- lines) at(line, text.trim) {
    var rest = text
    var more = true
    while (more) rest match {
      case labelDef(name, after) =>
        if (name.head.isDigit) locals += Local(name, stmts.size, section, sizes(section))
        else if (defined(name)) fail(s"$name defined twice")
        else labels(name) = (section, sizes(section))
        rest = after
      case _ => more = false
    }
    rest = rest.trim
    if (rest.nonEmpty) {
      val (op, tail) = rest.span(!_.isWhitespace)
      val args       = operands(tail)
      op match {
        case ".text" | ".data" | ".bss" => section = sectionOf(op)
        case ".section"                 => section = sectionOf(args.headOption.getOrElse(fail("missing section name")))
        case ".globl" | ".global" | ".option" => // nothing to do (.option: rvc, push, pop, ...)
        case ".equ" | ".set"                  =>
          val (name, value) = args match {
            case List(n, v) => (n, v)
            case _          => fail(s"$op needs a name and a value")
          }
          if (defined(name)) fail(s"$name defined twice")
          equs(name) = constant(value)
          equSeq(name) = stmts.size
        case _ =>
          if (section == "bss" && !(op == ".zero" || op == ".space" || alignments(op)))
            fail(s"$op in .bss, which holds no data")
          val n = size(op, args, sizes(section))
          stmts += Stmt(line, text.trim, op, args, section, sizes(section), n)
          sizes(section) += n
          if (sizes(section) > maxSize) fail(s".$section is larger than ${maxSize >> 20} MB")
      }
    }
  }

  // the labels of .text are aligned only if .text starts at an address aligned as it requires
  if (base % sectionAlign("text") != 0) fail(f"base 0x$base%x is not aligned to the .align of .text")

  private def align4(n: Long)           = (n + 3) & ~3L
  private def alignUp(n: Long, a: Long) = (n + a - 1) & -a
  // as GNU as, .text ends padded (with nops) to its largest alignment, and .data comes after that
  private val textEnd = sizes("text")
  sizes("text") = alignUp(textEnd, sectionAlign("text")).toInt
  private val sectionBase = {
    val data = alignUp(base + sizes("text"), sectionAlign("data"))
    Map("text" -> base, "data" -> data, "bss" -> alignUp(data + sizes("data"), sectionAlign("bss")))
  }
  private def address(section: String, offset: Int): Long = sectionBase(section) + offset

  // ---- expressions

  /** `1b` is the closest `1:` at or before statement `seq`, `1f` the closest after it. */
  private def symbol(name: String, seq: Int): Long = name match {
    case localRef(n, dir) =>
      val found =
        if (dir == "b") locals.filter(l => l.number == n && l.seq <= seq).lastOption
        else locals.find(l => l.number == n && l.seq > seq)
      found.map(l => address(l.section, l.offset)).getOrElse(fail(s"no label $n: for $name"))
    case _ =>
      equs
        .get(name)
        .orElse(labels.get(name).map { case (s, o) => address(s, o) })
        .getOrElse(fail(s"unknown symbol $name"))
  }

  private def hi20(x: Long): Long = ((x + 0x800) >> 12) & 0xfffff
  private def lo12(x: Long): Long = ((x & 0xfff) ^ 0x800) - 0x800

  /** The value of `expr` at address `pc`, with `sym` giving the value of a symbol, and whether it uses
    * a label (then it is an address). A `.equ` symbol defined after statement number `seq` counts as
    * a label: pass 1 did not know its value, so it must expand the statement to the same size.
    */
  private def evaluate(expr: String, pc: Long, sym: String => Long, seq: Int = Int.MaxValue): (Long, Boolean) = {
    var i              = 0
    var symbolic       = false
    def ws(): Unit     = while (i < expr.length && expr(i).isWhitespace) i += 1
    def word(): String = {
      val start = i
      while (i < expr.length && (expr(i).isLetterOrDigit || "_.$".contains(expr(i)))) i += 1
      expr.substring(start, i)
    }
    def expect(c: Char): Unit = {
      ws(); if (i < expr.length && expr(i) == c) i += 1 else fail(s"expected '$c' in '$expr'")
    }
    // GNU as's binary operators, from the lowest precedence: + and -; then |, & and ^; then *, /,
    // %, << and >> (not C's order). Values are 64-bit, as in GNU as.
    // (an operator of two characters must come before one that is its first character)
    def binary(next: () => Long, ops: Seq[(String, (Long, Long) => Long)]): Long = {
      var v    = next()
      var more = true
      while (more) {
        ws()
        ops.find { case (op, _) => expr.startsWith(op, i) } match {
          case Some((op, f)) => i += op.length; v = f(v, next())
          case None          => more = false
        }
      }
      v
    }
    def shift(b: Long)(f: => Long): Long = if (b < 0 || b > 63) 0 else f // as GNU as (with a warning)
    def sum(): Long                      = binary(() => logic(), Seq("+" -> (_ + _), "-" -> (_ - _)))
    def logic(): Long                    = binary(() => product(), Seq("|" -> (_ | _), "&" -> (_ & _), "^" -> (_ ^ _)))
    def product(): Long                  = binary(
      () => term(),
      Seq(
        "<<" -> ((a, b) => shift(b)(a << b)),
        ">>" -> ((a, b) => shift(b)(a >>> b)), // logical, as GNU as
        "*"  -> (_ * _),
        "/"  -> ((a, b) => if (b == 0) fail(s"division by zero in '$expr'") else a / b),
        "%"  -> ((a, b) => if (b == 0) fail(s"division by zero in '$expr'") else a % b)
      )
    )
    def term(): Long = {
      ws()
      if (i >= expr.length) fail(s"missing value in '$expr'")
      expr(i) match {
        case '-'  => i += 1; -term()
        case '~'  => i += 1; ~term()
        case '('  => i += 1; val v = sum(); expect(')'); v
        case '\'' =>
          i += 1
          if (i >= expr.length) fail(s"bad character in '$expr'")
          val c =
            if (expr(i) == '\\') { val (e, next) = escape(expr, i, expr.length); i = next; e }
            else { i += 1; expr(i - 1) }
          expect('\'')
          c.toLong
        case '%' =>
          i += 1
          val op = word()
          expect('(')
          val v = sum()
          expect(')')
          op match {
            case "hi"                  => hi20(v)
            case "lo"                  => lo12(v)
            case "pcrel_hi"            => if (!inPass1) pcrelHi(pc) = v; hi20(v - pc)
            case "pcrel_lo" if inPass1 => 0
            case "pcrel_lo"            =>
              lo12(pcrelHi.getOrElse(v, fail("%pcrel_lo needs the label of an earlier auipc with %pcrel_hi")) - v)
            case _ => fail(s"unknown operator %$op")
          }
        case c if c.isDigit =>
          val w = word()
          w match {
            case localRef(_, _) => symbolic = true; sym(w)
            // up to 64 bits, which wrap as in GNU as (0xffffffffffffffff is -1)
            case _ if w.startsWith("0x") || w.startsWith("0X") => java.lang.Long.parseUnsignedLong(w.drop(2), 16)
            case _ if w.startsWith("0b") || w.startsWith("0B") => java.lang.Long.parseUnsignedLong(w.drop(2), 2)
            // a leading 0 makes it octal, as in GNU as (010 is 8)
            case _ if w.length > 1 && w.head == '0' => java.lang.Long.parseUnsignedLong(w.drop(1), 8)
            case _                                  => java.lang.Long.parseUnsignedLong(w)
          }
        case c if c.isLetter || c == '_' || c == '.' || c == '$' =>
          val w = word()
          if (!equSeq.get(w).exists(_ <= seq)) symbolic = true
          sym(w)
        case c => fail(s"unexpected '$c' in '$expr'")
      }
    }
    val v =
      try sum()
      catch { case _: NumberFormatException => fail(s"bad number in '$expr'") }
    ws()
    if (i != expr.length) fail(s"unexpected '${expr.substring(i)}' in '$expr'")
    (v, symbolic)
  }

  // ---- instructions

  /** A value as a 32-bit Int: from -2^31 to 2^32 - 1, the upper half read as unsigned. */
  private def int32(v: Long): Int = {
    if (v < Int.MinValue || v > 0xffffffffL) fail(s"$v does not fit in 32 bits")
    v.toInt
  }

  /** The instructions of a statement, each followed by `padding` nops. */
  private def instructions(op: String, args: List[String], pc: Long, seq: Int): Seq[Inst] =
    expand(op, args, pc, seq).flatMap(i => i +: Seq.fill(padding)(Inst("addi")))

  /** The I-type instruction that does what R-type `op` does, with an immediate (`sltu` -> `sltiu`). */
  private def immediateForm(op: String): String = if (op == "sltu") "sltiu" else op + "i"

  /** The instructions of statement number `seq`, `op args`, at address `pc`. In pass 1 only their
    * number matters, so labels count as 0.
    */
  private def expand(op: String, args: List[String], pc: Long, seq: Int): Seq[Inst] = {
    def r(s: String)                      = Reg(s)
    def value(e: String): (Long, Boolean) =
      evaluate(e, pc, if (inPass1) n => equs.getOrElse(n, 0L) else symbol(_, seq), seq)
    def imm(e: String): Int = { // a signed immediate: 0xffffffff is out of range, not -1 (only li wraps)
      val v = value(e)._1
      if (v < Int.MinValue || v > Int.MaxValue) fail(s"$v is out of range")
      v.toInt
    }
    // a branch or jump target: with a label, an address; a plain number is an offset
    def target(e: String): Int = { val (v, isAddress) = value(e); int32(if (isAddress) v - pc else v) }
    def upper(e: String): Int  = { // the operand of lui and auipc: the upper 20 bits
      val v = value(e)._1
      if (v < 0 || v > 0xfffff) fail(s"$v is not a 20-bit value (use li, or %hi)")
      (v << 12).toInt
    }
    object Mem { // an operand offset(rs1), as (offset, rs1)
      def unapply(e: String): Option[(Int, Int)] = e match {
        case memRef(off, reg) if Reg.isReg(reg) => Some((if (off.trim.isEmpty) 0 else imm(off), r(reg)))
        case _                                  => None
      }
    }
    // auipc plus an instruction that adds the low 12 bits: reaches `sym` from anywhere
    def far(sym: String, rd: Int)(low: Int => Inst): Seq[Inst] = {
      val delta = value(sym)._1 - pc
      Seq(Inst("auipc", rd, imm = (hi20(delta) << 12).toInt), low(lo12(delta).toInt))
    }
    def fenceSet(s: String): Int =
      if (s.isEmpty || !s.forall("iorw".contains(_))) fail(s"bad fence operand '$s'")
      else s.distinct.map(c => 8 >> "iorw".indexOf(c)).sum
    val format = Rv32i.encodings.get(op).map(_.format)

    (op, args) match {
      case ("nop", Nil)        => Seq(Inst("addi"))
      case ("li", List(rd, e)) =>
        // lui sets the upper 20 bits, rounded up when bit 11 is set (as addi sign-extends the low 12).
        // An address is not known in pass 1, so it always takes both instructions.
        val (v, isAddress) = value(e)
        val x              = int32(v)
        val (hi, lo)       = ((hi20(x) << 12).toInt, lo12(x).toInt)
        if (!isAddress && hi == 0) Seq(Inst("addi", r(rd), imm = lo))
        else if (!isAddress && lo == 0) Seq(Inst("lui", r(rd), imm = hi))
        else Seq(Inst("lui", r(rd), imm = hi), Inst("addi", r(rd), r(rd), imm = lo))
      case ("la" | "lla", List(rd, sym))  => far(sym, r(rd))(lo => Inst("addi", r(rd), r(rd), imm = lo))
      case ("call", List(sym))            => far(sym, 1)(lo => Inst("jalr", 1, 1, imm = lo))
      case ("tail", List(sym))            => far(sym, 6)(lo => Inst("jalr", 0, 6, imm = lo))
      case ("mv", List(rd, rs))           => Seq(Inst("addi", r(rd), r(rs)))
      case ("not", List(rd, rs))          => Seq(Inst("xori", r(rd), r(rs), imm = -1))
      case ("neg", List(rd, rs))          => Seq(Inst("sub", r(rd), 0, r(rs)))
      case ("seqz", List(rd, rs))         => Seq(Inst("sltiu", r(rd), r(rs), imm = 1))
      case ("snez", List(rd, rs))         => Seq(Inst("sltu", r(rd), 0, r(rs)))
      case ("sltz", List(rd, rs))         => Seq(Inst("slt", r(rd), r(rs), 0))
      case ("sgtz", List(rd, rs))         => Seq(Inst("slt", r(rd), 0, r(rs)))
      case ("beqz", List(rs, t))          => Seq(Inst("beq", rs1 = r(rs), imm = target(t)))
      case ("bnez", List(rs, t))          => Seq(Inst("bne", rs1 = r(rs), imm = target(t)))
      case ("blez", List(rs, t))          => Seq(Inst("bge", rs2 = r(rs), imm = target(t)))
      case ("bgez", List(rs, t))          => Seq(Inst("bge", rs1 = r(rs), imm = target(t)))
      case ("bltz", List(rs, t))          => Seq(Inst("blt", rs1 = r(rs), imm = target(t)))
      case ("bgtz", List(rs, t))          => Seq(Inst("blt", rs2 = r(rs), imm = target(t)))
      case ("bgt", List(a, b, t))         => Seq(Inst("blt", rs1 = r(b), rs2 = r(a), imm = target(t)))
      case ("ble", List(a, b, t))         => Seq(Inst("bge", rs1 = r(b), rs2 = r(a), imm = target(t)))
      case ("bgtu", List(a, b, t))        => Seq(Inst("bltu", rs1 = r(b), rs2 = r(a), imm = target(t)))
      case ("bleu", List(a, b, t))        => Seq(Inst("bgeu", rs1 = r(b), rs2 = r(a), imm = target(t)))
      case ("j", List(t))                 => Seq(Inst("jal", 0, imm = target(t)))
      case ("jal", List(t))               => Seq(Inst("jal", 1, imm = target(t)))
      case ("jr", List(rs))               => Seq(Inst("jalr", 0, r(rs)))
      case ("jr", List(rs, e))            => Seq(Inst("jalr", 0, r(rs), imm = imm(e))) // jr rs, offset
      case ("jalr", List(rs))             => Seq(Inst("jalr", 1, r(rs)))
      case ("ret", Nil)                   => Seq(Inst("jalr", 0, 1))
      case ("fence", Nil)                 => Seq(Inst("fence", imm = 0xff))            // fence iorw, iorw
      case ("fence", List(p, s))          => Seq(Inst("fence", imm = fenceSet(p) << 4 | fenceSet(s)))
      case ("ecall" | "ebreak", Nil)      => Seq(Inst(op))
      case ("jal", List(rd, t))           => Seq(Inst("jal", r(rd), imm = target(t)))
      case ("jalr", List(rd, Mem(o, rs))) => Seq(Inst("jalr", r(rd), rs, imm = o))
      case ("jalr", List(rd, rs, e))      => Seq(Inst("jalr", r(rd), r(rs), imm = imm(e)))
      case (_, List(rd, Mem(o, rs))) if Rv32i.isLoad(op) => Seq(Inst(op, r(rd), rs, imm = o))
      case (_, List(rd, sym)) if Rv32i.isLoad(op)        => // lw rd, symbol
        // rd holds the address first: x0 cannot (the load would read address lo, not the symbol)
        if (r(rd) == 0) fail(s"$op: $rd cannot hold the address of $sym")
        far(sym, r(rd))(lo => Inst(op, r(rd), r(rd), imm = lo))
      // GNU as also takes an immediate for the register-register ALU instructions: sll a0, a0, 1
      case (_, List(rd, rs1, e)) if format.contains(Format.R) && !Reg.isReg(e) && Rv32i.isAluImm(immediateForm(op)) =>
        Seq(Inst(immediateForm(op), r(rd), r(rs1), imm = imm(e)))
      case (_, List(rd, rs1, rs2)) if format.contains(Format.R)     => Seq(Inst(op, r(rd), r(rs1), r(rs2)))
      case (_, List(rd, rs1, e)) if Rv32i.isAluImm(op)              => Seq(Inst(op, r(rd), r(rs1), imm = imm(e)))
      case (_, List(rs2, Mem(o, rs1))) if format.contains(Format.S) => Seq(Inst(op, rs1 = rs1, rs2 = r(rs2), imm = o))
      case (_, List(rs2, sym, rt)) if format.contains(Format.S)     => // sw rs2, symbol, rt
        // rt holds the address: x0 cannot, and rs2 would be overwritten before it is stored
        if (r(rt) == 0 || r(rt) == r(rs2)) fail(s"$op: the address register $rt must be neither x0 nor $rs2")
        far(sym, r(rt))(lo => Inst(op, rs1 = r(rt), rs2 = r(rs2), imm = lo))
      case (_, List(rs1, rs2, t)) if format.contains(Format.B) =>
        Seq(Inst(op, rs1 = r(rs1), rs2 = r(rs2), imm = target(t)))
      case (_, List(rd, e)) if format.contains(Format.U) => Seq(Inst(op, r(rd), imm = upper(e)))
      case _                                             => fail(s"unknown instruction, or wrong operands for $op")
    }
  }

  // ---- pass 2: the bytes of each section

  inPass1 = false
  private val bytes = Map("text" -> new Array[Byte](sizes("text")), "data" -> new Array[Byte](sizes("data")))

  private def put(sect: String, offset: Int, value: Long, n: Int): Unit =
    for (k <- 0 until n) bytes(sect)(offset + k) = (value >> (8 * k)).toByte

  for ((s, seq) <- stmts.zipWithIndex if s.section != "bss") at(s.line, s.text) {
    val pc                       = address(s.section, s.offset)
    def values(width: Int): Unit = for ((e, k) <- s.args.zipWithIndex) {
      val v = evaluate(e, pc, symbol(_, seq))._1
      if (v < -(1L << (8 * width - 1)) || v >= (1L << (8 * width))) fail(s"$v does not fit in $width bytes")
      put(s.section, s.offset + width * k, v, width)
    }
    s.op match {
      case ".word" | ".long"               => values(4)
      case ".half" | ".short"              => values(2)
      case ".byte"                         => values(1)
      case ".ascii" | ".string" | ".asciz" =>
        val zero = if (s.op == ".ascii") 0 else 1
        s.args.foldLeft(s.offset) { (o, a) =>
          val b = string(a); b.copyToArray(bytes(s.section), o); o + b.length + zero
        }
      case ".fill" => // repeat copies of value, each `size` bytes (little-endian)
        val (repeat, each) = fill(s.args)
        val value          = s.args.lift(2).map(evaluate(_, pc, symbol(_, seq))._1).getOrElse(0L)
        // as GNU as: a size above 4 gets the value's low 4 bytes, then zeros
        val low = if (each > 4) value & 0xffffffffL else value
        for (k <- 0L until repeat) put(s.section, s.offset + (k * each).toInt, low, each.toInt)
      case ".zero" | ".space" => // the arrays start as zeros
      case a if alignments(a) => // padding: zeros in .data, nops in .text
        val pad = padding(a, s.args.head, s.offset) // one operand: checked in pass 1
        if (s.section == "text" && pad > 0) {
          // as GNU as: a zero byte up to a half-word, the 2-byte c.nop (0x0001) up to a word, then
          // nops (only after data such as .byte is the offset not a whole word)
          val half = (s.offset + 1) & ~1
          val word = (s.offset + 3) & ~3
          if (half < word && word <= s.offset + pad) put("text", half, 0x0001, 2)
          for (o <- word until s.offset + pad by 4) put("text", o, Rv32i.encode(Inst("addi")), 4)
        }
      case op =>
        val insts = instructions(op, s.args, pc, seq)
        if (4 * insts.size != s.size) fail("the expansion changed size between the passes") // a bug in expand
        for ((inst, k) <- insts.zipWithIndex) put("text", s.offset + 4 * k, Rv32i.encode(inst), 4)
    }
  }
  for (o <- align4(textEnd).toInt until sizes("text") by 4) put("text", o, Rv32i.encode(Inst("addi")), 4)

  val program: Program = {
    def words(b: Array[Byte]): IndexedSeq[Long] =
      b.padTo(align4(b.length.toLong).toInt, 0.toByte)
        .grouped(4)
        .map(_.zipWithIndex.map { case (x, k) => (x & 0xffL) << (8 * k) }.sum)
        .toIndexedSeq
    Program(
      base,
      // the gap up to an aligned .data is zeros; an empty .data adds nothing (as GNU ld)
      if (bytes("data").isEmpty) words(bytes("text"))
      else words(bytes("text").padTo((sectionBase("data") - base).toInt, 0.toByte)) ++ words(bytes("data")),
      labels.map { case (n, (s, o)) => n -> address(s, o) }.toMap
    )
  }
}
