package riscv

import scala.util.Random

/** Constrained random RV32I programs, in assembly, for comparing cores with the golden model
  * ([[IsaSim]]). Every program ends with `ecall` and halts by construction:
  *
  *   - branches and jumps only go forward, by at most a few slots, to the start of a slot of the
  *     program (a slot is one instruction, or a pair: `auipc s10` + `jalr`, so that `jalr` always
  *     uses the address its `auipc` just computed, or `addi` + a load that uses it as its base);
  *   - loads and stores use `s11` (never written) as the base of a word-aligned 1 KB data area,
  *     with offsets inside it that are multiples of the access size (some loads use a base of
  *     their own, set from `s11` just before, with a negative offset, and overwrite it);
  *   - no instruction that halts (`ebreak`, illegal words) and no CSR instruction (there are none).
  *
  * A failing program can be saved as it is (it is a `.S` file) and replayed as a regression test.
  */
object RandomProgram {
  private val dataSize = 1024
  private val loads    = Seq("lb", "lbu", "lh", "lhu", "lw")
  // registers a random instruction may write: not s10 (jalr's base, written only as jalr's own
  // link) and not s11 (the data base); x0 too, to check that writes to it are ignored
  private val writable = (0 to 25) ++ (28 to 31)

  /** A program of `length` slots, then `ecall`. */
  def apply(seed: Long, length: Int = 200): String = {
    val rnd                    = new Random(seed)
    def pick[T](xs: Seq[T]): T = xs(rnd.nextInt(xs.size))
    def rd                     = s"x${pick(writable)}"
    def rs                     = s"x${rnd.nextInt(32)}"
    def imm12                  = rnd.nextInt(4096) - 2048
    // an offset in the data area that suits the size of load or store `op`
    def offset(op: String) = { val bytes = IsaSim.width(op); rnd.nextInt(dataSize / bytes) * bytes }
    // slot n is labelled L<n>; L<length> is the final ecall
    def forward(n: Int) = s"L${(n + 1 + rnd.nextInt(4)).min(length)}"

    val slots = (0 until length).map { n =>
      rnd.nextInt(12) match {
        case 0 | 1 | 2 =>
          s"${pick(Seq("add", "sub", "sll", "slt", "sltu", "xor", "srl", "sra", "or", "and"))} $rd, $rs, $rs"
        case 3 | 4                  => s"${pick(Seq("addi", "slti", "sltiu", "xori", "ori", "andi"))} $rd, $rs, $imm12"
        case 5                      => s"${pick(Seq("slli", "srli", "srai"))} $rd, $rs, ${rnd.nextInt(32)}"
        case 6                      => s"${pick(Seq("lui", "auipc"))} $rd, ${rnd.nextInt(1 << 20)}"
        case 7 if rnd.nextBoolean() =>
          // a base register of its own, in the middle of the data area (so the offset may be
          // negative), which the load then overwrites
          val (op, base) = (pick(loads), s"x${pick(writable.filter(_ != 0))}")
          s"addi  $base, s11, ${dataSize / 2}\n    $op $base, ${offset(op) - dataSize / 2}($base)"
        case 7  => val op = pick(loads); s"$op $rd, ${offset(op)}(s11)"
        case 8  => val op = pick(Seq("sb", "sh", "sw")); s"$op $rs, ${offset(op)}(s11)"
        case 9  => s"${pick(Seq("beq", "bne", "blt", "bge", "bltu", "bgeu"))} $rs, $rs, ${forward(n)}"
        case 10 => s"jal $rd, ${forward(n)}"
        case 11 if rnd.nextInt(4) == 0 => "fence"
        case 11 => // an odd offset too: jalr clears bit 0 of the target; sometimes rd is the base
          val link = if (rnd.nextInt(4) == 0) "s10" else rd
          s"auipc s10, %pcrel_hi(${forward(n)})\n    jalr  $link, %pcrel_lo(L$n)+${rnd.nextInt(2)}(s10)"
      }
    }
    // every register a program may read starts with a known value: a core's registers start unknown
    val init = (1 to 31).filter(_ != Reg("s11")).map(r => s"    li    x$r, ${rnd.nextInt()}") // s11: see la
    (Seq(s"# RandomProgram(seed = $seed, length = $length)", "    la    s11, data") ++ init ++
      slots.zipWithIndex.map { case (s, n) => s"L$n: $s" } ++
      Seq(s"L$length: ecall", "    .data", "    .align 2", s"data: .zero $dataSize")).mkString("", "\n", "\n")
  }
}
