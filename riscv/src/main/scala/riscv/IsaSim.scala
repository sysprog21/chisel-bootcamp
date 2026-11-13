package riscv

import scala.collection.mutable

/** Why a core stopped. There are no CSRs, so no trap handler: each of these halts the core (see
  * DESIGN.md).
  */
sealed trait Halt
object Halt {
  case object Ecall      extends Halt // how a program ends, with its result in a0
  case object Ebreak     extends Halt
  case object Illegal    extends Halt // not an RV32I instruction
  case object Misaligned extends Halt // a load or store address, or a jump or branch target, not aligned
  case object BadAddress extends Halt // a fetch, load or store outside the RAM
}

/** A load or store: its address, its size in bytes (1, 2 or 4) and the bytes read or written,
  * zero-extended (a load's sign extension shows in the register write).
  */
case class MemAccess(addr: Long, write: Boolean, bytes: Int, data: Long)

/** One retired instruction, as the RVFI-style retire port of a core reports it. `rd` is 0 when
  * nothing is written (and `rdValue` is then 0); values are 32-bit, unsigned. An instruction that
  * halts the core retires with `halt` set and changes nothing.
  */
case class Retire(
    pc: Long,
    inst: Long,
    nextPc: Long,
    rd: Int = 0,
    rdValue: Long = 0,
    mem: Option[MemAccess] = None,
    halt: Option[Halt] = None
)

/** The golden model: an RV32I interpreter that executes one instruction per `step` and reports it
  * as a [[Retire]] record, for comparison with the cores' retire ports. It is a teaching oracle
  * written with the course, not an independent reference, so it shares the course's reading of
  * the specification (and Rv32i.decode).
  *
  * The memory is one RAM of `size` bytes at `base`, holding `image` (from `Asm`) and zeros after it.
  */
class IsaSim(image: Seq[Long], val base: Long = Ram.base, val size: Int = Ram.bytes) {
  require(
    base >= 0 && base % 4 == 0 && size % 4 == 0 && size > 0 && base + size <= (1L << 32),
    "the RAM must be whole words"
  )
  require(image.size * 4L <= size, "the image is larger than the RAM")

  /** Loads `program` at the address it was assembled for. */
  def this(program: Program) = this(program.words, program.base)

  private val ram = new Array[Byte](size)
  for ((w, i) <- image.zipWithIndex; k <- 0 until 4) ram(4 * i + k) = (w >> (8 * k)).toByte

  private val x                     = new Array[Int](32)
  private var _pc: Long             = base
  private var _halted: Option[Halt] = None

  def pc: Long               = _pc
  def halted: Option[Halt]   = _halted
  def reg(n: Int): Long      = x(n) & 0xffffffffL
  def regs: IndexedSeq[Long] = (0 until 32).map(reg)

  private def inRam(addr: Long, bytes: Int) = addr >= base && addr + bytes <= base + size

  /** `bytes` bytes at `addr`, little-endian, zero-extended. */
  def read(addr: Long, bytes: Int): Long = {
    require(inRam(addr, bytes), f"0x$addr%08x is outside the RAM")
    bytesAt(addr, bytes)
  }

  private def lowBytes(v: Long, bytes: Int): Long = v & ((1L << (8 * bytes)) - 1)

  private def bytesAt(addr: Long, bytes: Int): Long = { // unchecked: the callers checked inRam
    var v = 0L
    for (k <- bytes - 1 to 0 by -1) v = v << 8 | (ram((addr - base).toInt + k) & 0xffL)
    v
  }

  private def write(addr: Long, bytes: Int, value: Long): Unit =
    for (k <- 0 until bytes) ram((addr - base).toInt + k) = (value >> (8 * k)).toByte

  // decoding is the slow part of a step; programs execute the same words again and again
  private val decoded = mutable.HashMap[Long, Option[Inst]]()

  /** The core halts at `pc` for reason `h`; the instruction retires but changes nothing. */
  private def stop(pc: Long, h: Halt, inst: Long): Retire = { _halted = Some(h); Retire(pc, inst, pc, halt = Some(h)) }

  /** Executes the instruction at `pc`. A fetch outside the RAM retires with `inst` 0. */
  def step(): Retire = {
    require(_halted.isEmpty, s"the core has halted (${_halted.get})")
    if (!inRam(_pc, 4)) stop(_pc, Halt.BadAddress, 0)
    else {
      val word = bytesAt(_pc, 4)
      decoded.getOrElseUpdate(word, Rv32i.decode(word)) match {
        case None    => stop(_pc, Halt.Illegal, word)
        case Some(i) => execute(_pc, word, i)
      }
    }
  }

  private def execute(pc: Long, word: Long, i: Inst): Retire = {
    val a                  = x(i.rs1)
    val b                  = x(i.rs2)
    def u32(v: Long): Long = v & 0xffffffffL
    // Branches and stores have no rd field (decode gives 0), so they write nothing either
    def retire(next: Long, value: Int = 0, mem: Option[MemAccess] = None): Retire = {
      if (i.rd != 0) x(i.rd) = value
      _pc = u32(next) // the pc wraps at 2^32, as a core's 32-bit pc does
      Retire(pc, word, _pc, i.rd, if (i.rd == 0) 0 else u32(value), mem)
    }
    // a jump or a taken branch; it halts, without writing its link register, if the target is misaligned
    def jump(target: Long): Retire =
      if (target % 4 != 0) stop(pc, Halt.Misaligned, word) else retire(target, (pc + 4).toInt)
    // a misaligned address halts as Misaligned even if it is also outside the RAM
    def access(addr: Long, bytes: Int)(body: => Retire): Retire =
      if (addr % bytes != 0) stop(pc, Halt.Misaligned, word)
      else if (!inRam(addr, bytes)) stop(pc, Halt.BadAddress, word)
      else body
    lazy val addr  = u32(a.toLong + i.imm) // of loads, stores and jalr
    lazy val bytes = IsaSim.width(i.name)  // of loads and stores

    i.name match {
      case "lui"                                           => retire(pc + 4, i.imm)
      case "auipc"                                         => retire(pc + 4, (pc + i.imm).toInt)
      case "jal"                                           => jump(u32(pc + i.imm))
      case "jalr"                                          => jump(addr & ~1L)
      case "beq" | "bne" | "blt" | "bge" | "bltu" | "bgeu" =>
        if (IsaSim.taken(i.name, a, b)) jump(u32(pc + i.imm)) else retire(pc + 4)
      case "lb" | "lh" | "lw" | "lbu" | "lhu" =>
        access(addr, bytes) {
          val raw   = bytesAt(addr, bytes)
          val value = IsaSim.loadValue(i.name, raw)
          retire(pc + 4, value, Some(MemAccess(addr, write = false, bytes, raw)))
        }
      case "sb" | "sh" | "sw" =>
        access(addr, bytes) {
          val data = lowBytes(u32(b), bytes)
          write(addr, bytes, data)
          retire(pc + 4, mem = Some(MemAccess(addr, write = true, bytes, data)))
        }
      // one in-order core: nothing to order. Its rd field is reserved, so it is not written.
      case "fence"                      => _pc = u32(pc + 4); Retire(pc, word, _pc)
      case "ecall"                      => stop(pc, Halt.Ecall, word)
      case "ebreak"                     => stop(pc, Halt.Ebreak, word)
      case name if Rv32i.isAluImm(name) => retire(pc + 4, IsaSim.alu(name.filterNot(_ == 'i'), a, i.imm)) // addi -> add
      case name                         => retire(pc + 4, IsaSim.alu(name, a, b))
    }
  }

  /** Steps until the core halts, at most `maxSteps` times; returns what retired. */
  def run(maxSteps: Int = 1000000): Seq[Retire] = {
    val trace = mutable.ArrayBuffer[Retire]()
    while (_halted.isEmpty && trace.size < maxSteps) trace += step()
    trace.toSeq
  }
}

object IsaSim {

  /** Whether branch `name` (`beq`, ..., `bgeu`) is taken for registers `a` and `b`. */
  def taken(name: String, a: Int, b: Int): Boolean = name match {
    case "beq"  => a == b
    case "bne"  => a != b
    case "blt"  => a < b
    case "bge"  => a >= b
    case "bltu" => Integer.compareUnsigned(a, b) < 0
    case "bgeu" => Integer.compareUnsigned(a, b) >= 0
    case _      => throw new IllegalArgumentException(s"not a branch: $name")
  }

  /** The value load `name` (`lb`, ..., `lhu`) writes to rd, from the bytes it read (`raw`, zero-extended). */
  def loadValue(name: String, raw: Long): Int =
    if (name.endsWith("u")) raw.toInt else Rv32i.signExtend(raw.toInt, 8 * width(name))

  /** The size in bytes of a load or store (`lb`, `sh`, `lw`, ...). */
  def width(name: String): Int = name(1) match {
    case 'b' => 1
    case 'h' => 2
    case 'w' => 4
  }

  /** The result of a register-register operation (`add`, ..., `and`); Int behaves like a 32-bit
    * register, and shifts use the low 5 bits of `b`.
    */
  def alu(op: String, a: Int, b: Int): Int = op match {
    case "add"  => a + b
    case "sub"  => a - b
    case "sll"  => a << b
    case "slt"  => if (a < b) 1 else 0
    case "sltu" => if (Integer.compareUnsigned(a, b) < 0) 1 else 0
    case "xor"  => a ^ b
    case "srl"  => a >>> b
    case "sra"  => a >> b
    case "or"   => a | b
    case "and"  => a & b
    case _      => throw new IllegalArgumentException(s"not an ALU operation: $op")
  }

  /** Assembles `source` and runs it until it halts. */
  def run(source: String, maxSteps: Int = 1000000): (IsaSim, Seq[Retire]) = {
    val sim = new IsaSim(Asm.assemble(source))
    (sim, sim.run(maxSteps))
  }
}
