package riscv

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** The rv32ui tests of riscv-tests (riscv/programs/riscv-tests, imported by
  * tools/import-riscv-tests.py). A test passes if it ends with ECALL and a0 = 0; otherwise a0 is
  * (the number of the failing test << 1) | 1.
  */
class ComplianceSpec extends AnyFlatSpec with ChiselSim {
  val dir   = "programs/riscv-tests/rv32ui"
  val tests = TestPrograms.names(dir)
  // each test assembled once, for the golden model and the core alike
  val images = tests.map(name => name -> Asm(TestPrograms.source(dir, name))).toMap

  "The rv32ui tests" should "be there" in {
    assert(tests.size == 40 && tests.contains("add") && tests.contains("ld_st"))
  }

  they should "pass on the golden model" in {
    for (name <- tests) {
      val sim = new IsaSim(images(name))
      sim.run(100000)
      assert(sim.halted.contains(Halt.Ecall) && sim.reg(Reg("a0")) == 0, s"$name: a0 = ${sim.reg(Reg("a0"))}")
    }
  }

  they should "pass on the single-cycle core, retiring like the golden model" in {
    simulate(new SingleCycleSystem) { c =>
      for (name <- tests) {
        CoSim.compare(c, images(name)).foreach(d => fail(s"$name: $d"))
        assert(CoSim.reg(c, Reg("a0")) == 0, name)
      }
    }
  }
}
