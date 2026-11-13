"""Run from the repository root: python3 tests/test_riscv_import.py.

Checks that a fresh Scala kernel can use the riscv/ project through source/load-riscv.sc.
"""

from kernel import scala_kernel

with scala_kernel() as run:
    run('interp.load.module(os.pwd / "source" / "load-ivy.sc")')
    run('interp.load.module(os.pwd / "source" / "load-riscv.sc")')
    out = run("""
import chisel3._
import riscv._
println(AluOp.all.map(_.toString).mkString(" "))
println(classOf[Alu].getName)
""")
    assert "Add" in out and "riscv.Alu" in out, out
    # unchanged sources: the second load must reuse the classes instead of running sbt again
    out = run('interp.load.module(os.pwd / "source" / "load-riscv.sc")')
    assert "Compiling riscv/" not in out, out
    print("load-riscv.sc: OK")
