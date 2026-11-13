package riscv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** Checks the ALU against a Scala model on random operands (a fixed seed, so failures repeat). */
class AluSpec extends AnyFlatSpec with ChiselSim {
  // Scala's Int behaves like a 32-bit register: it wraps around, `<<`, `>>` and `>>>` use the low 5
  // bits of the shift amount, and `>>` is arithmetic
  def model(op: AluOp.Type, a: Int, b: Int): Int = op match {
    case AluOp.Add  => a + b
    case AluOp.Sub  => a - b
    case AluOp.Sll  => a << b
    case AluOp.Slt  => if (a < b) 1 else 0
    case AluOp.Sltu => if (Integer.compareUnsigned(a, b) < 0) 1 else 0
    case AluOp.Xor  => a ^ b
    case AluOp.Srl  => a >>> b
    case AluOp.Sra  => a >> b
    case AluOp.Or   => a | b
    case AluOp.And  => a & b
  }

  private def unsigned(x: Int) = BigInt(Integer.toUnsignedLong(x))

  "Alu" should "match the Scala model for every operation" in {
    simulate(new Alu) { c =>
      val rnd = new scala.util.Random(1)
      // random values, and the corner cases random values rarely hit
      val corners   = Seq(0, 1, -1, Int.MaxValue, Int.MinValue)
      def operand() = if (rnd.nextInt(4) == 0) corners(rnd.nextInt(corners.size)) else rnd.nextInt()
      for (op <- AluOp.all; _ <- 0 until 200) {
        val (a, b) = (operand(), operand())
        c.io.op.poke(op)
        c.io.a.poke(unsigned(a).U)
        c.io.b.poke(unsigned(b).U)
        c.io.out.expect(unsigned(model(op, a, b)).U, s"$op($a, $b)")
      }
    }
  }
}
