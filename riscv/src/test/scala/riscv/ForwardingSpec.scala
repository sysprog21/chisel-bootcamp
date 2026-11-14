package riscv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** The forwarding core with a data memory that answers `delay` cycles late. */
class SlowDataSystem(delay: Int) extends RiscvSystem {
  val core = Module(new ForwardingCore)
  val ram  = Module(new Ram)
  val slow = Module(new Delay(delay))
  ram.io.imem <> core.io.imem
  slow.io.in <> core.io.dmem
  ram.io.dmem <> slow.io.out
  ram.io.load := io.load
  io.retire   := core.io.retire
  io.halted   := core.io.halted
  io.dbg <> core.io.dbg
}

class ForwardingSpec extends AnyFlatSpec with ChiselSim {
  "Forward" should "take the newest result for the register, and never one for x0" in {
    simulate(new Forward) { c =>
      def check(rs: Int, ex: Option[(Int, Int)], wb: Option[(Int, Int)], expected: Int) = {
        c.io.rs.poke(rs.U)
        c.io.regValue.poke(1.U)
        for ((port, r) <- Seq(c.io.exMem -> ex, c.io.memWb -> wb)) {
          port.valid.poke(r.isDefined.B)
          port.bits.rd.poke(r.map(_._1).getOrElse(0).U)
          port.bits.value.poke(r.map(_._2).getOrElse(0).U)
        }
        c.io.value.expect(expected.U, s"rs $rs, EX/MEM $ex, MEM/WB $wb")
      }
      check(5, None, None, 1)
      check(5, Some(5 -> 2), None, 2)
      check(5, None, Some(5 -> 3), 3)
      check(5, Some(5 -> 2), Some(5 -> 3), 2) // the newest
      check(5, Some(6 -> 2), Some(5 -> 3), 3)
      check(5, Some(6 -> 2), Some(7 -> 3), 1)
      check(0, Some(0 -> 2), Some(0 -> 3), 1) // x0 is never forwarded
    }
  }

  // every hazard of the five-stage pipeline, unpadded
  val hazards = Seq(
    "EX/MEM to EX"             -> "li a0, 5\naddi a1, a0, 1\necall",
    "MEM/WB to EX"             -> "li a0, 5\nnop\naddi a1, a0, 1\necall",
    "WB to ID"                 -> "li a0, 5\nnop\nnop\naddi a1, a0, 1\necall",
    "the newest result wins"   -> "li a0, 1\nli a0, 2\nadd a1, a0, a0\necall",
    "both operands"            -> "li a0, 3\nli a1, 4\nsub a2, a1, a0\necall",
    "x0 is not forwarded"      -> "addi zero, zero, 7\nadd a0, zero, zero\necall",
    "load-use"                 -> "la t0, d\nlw a0, 0(t0)\naddi a1, a0, 1\necall\n.data\nd: .word 41",
    "load-use, second operand" -> "la t0, d\nlw a0, 0(t0)\nadd a1, t0, a0\necall\n.data\nd: .word 41",
    "load then store"          -> "la t0, d\nlw a0, 0(t0)\nsw a0, 4(t0)\nlw a1, 4(t0)\necall\n.data\nd: .word 41, 0",
    "load into the base"       -> "la t0, d\nlw t0, 0(t0)\nlw a0, 0(t0)\necall\n.data\nd: .word d",
    "branch on a fresh value"  -> "li a0, 1\nbnez a0, 1f\nebreak\n1: ecall",
    "branch on a loaded value" -> "la t0, d\nlw a0, 0(t0)\nbnez a0, 1f\nebreak\n1: ecall\n.data\nd: .word 1",
    "jalr on a fresh base"     -> "la t0, 1f\njalr ra, 0(t0)\nebreak\n1: ecall",
    "jal link used at once"    -> "jal ra, 1f\nebreak\n1: addi a0, ra, 0\necall",
    "wrong-path stores" -> "la t0, d\nj 1f\nsw t0, 0(t0)\nsw t0, 4(t0)\n1: lw a0, 0(t0)\necall\n.data\nd: .word 7, 8",
    "a store behind a halt" -> "la t0, d\nebreak\nsw t0, 0(t0)\n.data\nd: .word 7"
  )

  "ForwardingCore" should "resolve every hazard without nops, like the golden model" in {
    simulate(new ForwardingSystem) { c =>
      for ((name, source) <- hazards) CoSim.compare(c, Asm(source), maxCycles = 1000).foreach(d => fail(s"$name: $d"))
    }
  }

  it should "run the committed, regression and random programs unpadded" in {
    simulate(new ForwardingSystem) { c =>
      for ((name, result) <- Seq("fib" -> 55, "fact" -> 120)) {
        CoSim.compare(c, Asm(TestPrograms.source("programs/src", name))).foreach(d => fail(s"$name: $d"))
        assert(CoSim.reg(c, riscv.Reg("a0")) == result, name)
      }
      for (name <- TestPrograms.names("programs/regress"))
        CoSim.compare(c, Asm(TestPrograms.source("programs/regress", name))).foreach(d => fail(s"$name: $d"))
      for (seed <- 1L to 20L)
        CoSim.compare(c, Asm(RandomProgram(seed))).foreach(d => fail(s"RandomProgram($seed): $d"))
    }
  }

  it should "pass the rv32ui tests" in {
    val dir = "programs/riscv-tests/rv32ui"
    simulate(new ForwardingSystem) { c =>
      for (name <- TestPrograms.names(dir)) {
        CoSim.compare(c, Asm(TestPrograms.source(dir, name))).foreach(d => fail(s"$name: $d"))
        assert(CoSim.reg(c, riscv.Reg("a0")) == 0, name)
      }
    }
  }

  it should "wait for a data memory that answers late" in {
    simulate(new SlowDataSystem(delay = 2)) { c =>
      for (name <- Seq("fib", "fact"))
        CoSim.compare(c, Asm(TestPrograms.source("programs/src", name))).foreach(d => fail(s"$name: $d"))
      for ((name, source) <- hazards) CoSim.compare(c, Asm(source), maxCycles = 1000).foreach(d => fail(s"$name: $d"))
      for (seed <- 1L to 5L)
        CoSim.compare(c, Asm(RandomProgram(seed))).foreach(d => fail(s"RandomProgram($seed): $d"))
      for (name <- Seq("ld_st", "st_ld", "lw", "sb")) {
        val dir = "programs/riscv-tests/rv32ui"
        CoSim.compare(c, Asm(TestPrograms.source(dir, name))).foreach(d => fail(s"$name: $d"))
      }
    }
  }

  it should "take fewer cycles than the nop-padded pipeline of 5.6" in {
    val programs = Seq("fib", "fact").map(n => TestPrograms.source("programs/src", n))
    def cycles(system: => RiscvSystem, images: Seq[Seq[Long]]): Seq[Int] = {
      var result = Seq.empty[Int]
      simulate(system) { c =>
        result = images.map { image =>
          CoSim.load(c, image)
          val (trace, n) = CoSim.timed(c)
          assert(trace.lastOption.exists(_.halt.isDefined), "the program did not halt")
          n
        }
      }
      result
    }
    val forward = cycles(new ForwardingSystem, programs.map(Asm(_)))
    val padded  = cycles(new PipelinedSystem, programs.map(Asm.padded(_, 2)))
    for (((f, p), source) <- forward.zip(padded).zip(programs)) {
      val instructions = new IsaSim(Asm(source)).run().size
      println(f"$instructions%4d instructions: $f%4d cycles (CPI ${f.toDouble / instructions}%.2f), padded 5.6: $p%4d")
      assert(f < p && f < 2 * instructions)
    }
  }
}
