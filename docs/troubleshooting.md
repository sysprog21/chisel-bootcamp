# Troubleshooting

Failures seen while building and running the bootcamp, with the text they print and the fix. Most of
them happen outside the Docker image, whose setup avoids them.

## A cell runs forever, with no output

**What you see:** the cell stays busy (`[*]`) and prints nothing, after code that builds a module,
for example `ChiselStage.emitSystemVerilog(new MyModule)` while `MyModule` still has a `???` exercise
in it. Interrupting does not help, and every later cell stays busy too. The kernel's own log (the
terminal that runs `jupyter lab`, or `docker logs` of the bootcamp's container) has:

```
Exception in thread "main" scala.MatchError: ArraySeq() (of class scala.collection.immutable.ArraySeq$ofRef)
	at almond.interpreter.ExecuteError$.highlightFrame(ExecuteError.scala:21)
```

**Why:** Chisel shortens the stack trace of an error raised while it builds a module, with frames
named `...`, and Almond 0.14.5 fails on those frames while it formats the error.

**Fix:** restart the kernel (Kernel, Restart Kernel) and run the Setup cells again. `getVerilog`,
`getFirrtl` and `simulate` (from `source/load-ivy.sc`) already show such errors; wrap anything else
that builds a module in `scrub`, e.g. `scrub(ChiselStage.emitSystemVerilog(new MyModule))`.

## The first Yosys cell takes a minute

**What you see:** in Modules 5.9 and 5.10, before the cell's own output:

```
Preparing to run yowasp-yosys. This might take a while...
```

This is not a failure: the image's Yosys is WebAssembly, compiled for your machine on its first run
in a container. Unlike the hang above, the cell finishes, and later runs are fast.

## `Missing dependency 'Add -Ytasty-reader to scalac options ...'`

**What you see:** for example on `import scala.util.Random`:

```
error while loading FromDigits, Missing dependency 'Add -Ytasty-reader to scalac options to parse the TASTy in .../scala3-library_3-3.3.6.jar(scala/util/FromDigits.tasty)', required by ...
```

**Why:** Chisel 7's dependencies put Scala 3 classes on the classpath, which the kernel's Scala 2.13
compiler reads only with `-Ytasty-reader`.

**Fix:** run the first Setup cell, `interp.load.module(os.pwd / "source" / "load-ivy.sc")`, which
sets the option; in a notebook of your own, make it the first cell.

## `reference to Input is ambiguous`

**What you see:**

```
reference to Input is ambiguous;
it is imported twice in the same scope by
import chisel3._
and import _root_.almond.input.Input
```

**Why:** the kernel imports Almond's own `Input`, which clashes with Chisel's.

**Fix:** import Chisel's by name after `import chisel3._`, as the notebooks' Setup cells do:
`import chisel3.Input // avoid a name clash with almond.input.Input`.

## `%Error: -j accepts positive integer, but '0' is passed`

**What you see:** a simulation fails while ChiselSim compiles it, and the compilation log has:

```
%Error: -j accepts positive integer, but '0' is passed
%Error: Exiting due to 1 error(s)
```

**Why:** the Verilator on the `PATH` is older than version 5 (this text is from 4.210), and ChiselSim
passes it options of Verilator 5.

**Fix:** install Verilator 5 or newer, as [Install.md](../Install.md#verilator) describes, or use the
Docker image.

## `Killed`, or the kernel dies, while simulating

**What you see:** `sbt` prints `Killed` and stops, or JupyterLab says that the kernel appears to
have died and will restart automatically, in a cell that compiles a simulation.

**Why:** the container ran out of memory: Verilator runs a C++ compiler beside the JVM of the kernel
or of sbt, and Module 5's tests need more than 3 GB.

**Fix:** give the container at least 4 GB (Docker Desktop on macOS or Windows: Settings, Resources,
Memory; on Linux, Docker sets no limit unless you pass `--memory`), and close the kernels of
notebooks you no longer use (Kernel, Shut Down Kernel).

## A memory loaded from a file reads random values

**What you see:** no error while the module is built, but a `Mem` or `SyncReadMem` initialized with
`loadMemoryFromFileInline` reads values that are not in the file, so an `expect` on them fails.

**Why and fix:** ChiselSim randomizes memories after `$readmemh` loads the file; turn that off for
the simulation, as [Module 2.8](../2.8_memories.ipynb) explains and does:

```scala
simulate(new MyModule, randomization = chisel3.simulator.Randomization.random.copy(memories = false)) { c => ... }
```
