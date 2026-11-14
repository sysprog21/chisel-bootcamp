"""Run from the repository root: python3 tests/test_chiseltest_compat.py.

Checks what 2.6_chiselsim.ipynb says about the `chiseltest` compatibility package of Chisel 7, in
the kernel the notebooks use: it runs a ChiselTest-style test on ChiselSim, it resets the module for
one cycle first (as ChiselSim's `simulate` does), and its `fork` runs its body at once instead of
concurrently.
"""

from kernel import scala_kernel

with scala_kernel() as run:
    run('interp.load.module(os.pwd / "source" / "load-ivy.sc")')
    run("import chisel3._\nimport chisel3.Input // avoid a name clash with almond.input.Input")  # as the notebooks do
    run("""
class Delayed extends Module {
  val io = IO(new Bundle {
    val in  = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })
  io.out := RegNext(io.in, "hdeadbeef".U(32.W))
}
""")
    # only what the test needs, as the notebooks import only chisel3 and its simulator
    out = run("""
import chiseltest.{fork, ChiselScalatestTester, testableClock, testableUInt}

object Compat extends ChiselScalatestTester
var order = Vector.empty[String]
Compat.test(new Delayed) { c =>
  // registers start random, so the reset value shows that the module was reset
  c.io.out.expect("hdeadbeef".U)
  c.io.in.poke(7.U)
  val h = fork { c.clock.step(); order :+= "fork" }
  order :+= "main"
  h.join()
  c.io.out.expect(7.U)
}
println(s"ran; order: ${order.mkString(",")}")
""")
    # a concurrent fork would block on its step and let the main thread run first
    assert "ran; order: fork,main" in out, out
    print("chiseltest compatibility package: OK")
