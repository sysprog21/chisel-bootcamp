package riscv

import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** Replays riscv/programs/regress: programs that once exposed a bug, one test per program. */
class RegressSpec extends AnyFlatSpec with ChiselSim {
  val dir      = "programs/regress"
  val programs = Programs.names(dir)

  "The regression programs" should "be there" in {
    assert(programs.nonEmpty)
  }

  for (name <- programs)
    they should s"retire $name on the single-cycle core as on the golden model" in {
      simulate(new SingleCycleSystem) { c =>
        CoSim.compare(c, Asm(Programs.source(dir, name))).foreach(d => fail(d))
      }
    }
}
