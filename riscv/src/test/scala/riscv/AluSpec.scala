package riscv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** Checks the ALU against the golden model's ALU (IsaSim.alu) on random operands (a fixed seed,
  * so failures repeat).
  */
class AluSpec extends AnyFlatSpec with ChiselSim {
  def model(op: AluOp.Type, a: Int, b: Int): Int = IsaSim.alu(AluOp.mnemonic(op), a, b)

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
