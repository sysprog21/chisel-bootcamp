# Instructor guide

How to run the bootcamp as a course: a schedule, how the exercises are checked, the errors students
meet most, and how to host it for a class. It has not yet been reviewed by an instructor who ran
the course; corrections are welcome.

## The course in 14 weeks

The notebooks add up to about 23 hours (the README's outline times each). With a two-hour lab a
week they fill 14 weeks; weeks 3, 7, 8 and 11 run over two hours, week 14 leaves time for review,
and Module 5, the processor, takes the last five.

| Week | Notebooks | Topic |
|---|---|---|
| 1 | 0_demo, 1_intro_to_scala | Setup, enough Scala to start |
| 2 | 2.1, 2.2 | A first module, combinational logic |
| 3 | 2.3, 2.4 | Control flow, registers and reset |
| 4 | 2.5, 2.6 | An FIR filter; testing with ChiselSim, waveforms, assertions |
| 5 | 2.7, 2.8 | Enums, decoders, bundles; memories |
| 6 | 3.1, 3.2, 3.2_interlude | Parameters, collections, the standard library |
| 7 | 3.3, 3.4, 3.5 | Functional and object-oriented generators |
| 8 | 3.6, 4.1 | Types; FIRRTL and `firtool` |
| 9 | 4.2, 4.3, 4.4 | Passes over FIRRTL |
| 10 | 5.1, 5.2 | The RV32I ISA, the assembler; the ALU and registers |
| 11 | 5.3, 5.4, 5.5 | The decoder, a single-cycle core, the official tests |
| 12 | 5.6, 5.7 | The pipeline and its hazards |
| 13 | 5.8, 5.9 | A bus and a UART; debugging and a formal check |
| 14 | 5.10 | Lint, synthesis, an FPGA board; review |

Module 4 is the part to shorten for a hardware-only course (it is about the compiler, not the
hardware); Modules 2 and 5 are the core. Module 5's notebooks build on each other's exercises: a
student who skips one cannot run the next.

## Checking the exercises

- **Modules 2-4:** each exercise is a cell with `???` to fill in, followed by a cell that tests it:
  it errors until the answer is right; most then print `SUCCESS!!`. The hidden "Solution" block under each exercise gives an answer. A
  submission is the notebook; rerunning it from the top (Kernel, Restart Kernel and Run All Cells)
  checks it.
- **Module 5:** the exercises are Scala files under `riscv/src/main/scala/riscv/` with `???` to
  fill in, and the tests of the project check them: from a terminal in `riscv/`,
  `sbt "testOnly riscv.AluSpec"` for one part, `sbt test` for all. The notebooks also run the
  checks of their module. A submission is the `riscv/src/` directory.
- **Reference solutions** are in `riscv/solutions/` of the repository, which the Docker image leaves
  out. `tools/check-solutions.sh` (from a clone) checks that they pass every test, and that the
  student scaffold compiles; `runtest.py` runs every notebook as a student gets it.

## Errors students meet

[Troubleshooting](troubleshooting.md) has each error with its text and fix; mention these in the
first lab:

- [A cell that never finishes](troubleshooting.md#a-cell-runs-forever-with-no-output) after
  building a module with a `???` in it.
- [`Killed`, or a kernel that dies](troubleshooting.md#killed-or-the-kernel-dies-while-simulating):
  too little memory.
- **`NotImplementedError: an implementation is missing`**: an exercise, or an earlier one it
  depends on, is not done yet. In Module 5 the cells stop with it until the exercises of the
  earlier notebooks are done.
- **Cells run out of order**: a later cell uses a definition from a cell that was not run; run the
  notebook from the top.

## Hosting a class

Each student needs one container of the image (`sysprog21/chisel-bootcamp`, or a `chisel-<version>`
tag to keep a term on one release; the README lists the tags).

- **Memory: 4 GB per student**, for a JVM beside Verilator's C++ compiler (the
  [troubleshooting entry](troubleshooting.md#killed-or-the-kernel-dies-while-simulating) has the
  measurements).
- **CPU: 2 cores per student.** Verilator builds in parallel, so more cores speed up Module 5; one
  core works but is slower. The first Yosys cell in a container (5.9, 5.10) takes about a minute.
- **Disk:** the image is about 0.65 GB (amd64; Arm64 about 0.75 GB); each container writes little
  (`build/` holds simulation output).
- **An image of your own on top of it:** to keep it small, the image deletes files of some Alpine
  packages it keeps installed (Perl, Graphviz's image plugins and libraries, parts of GCC), so run
  `apk fix --no-cache $(cat /etc/bootcamp-pruned)` before `apk add` in your Dockerfile.
- **Arm64 machines** run the image too, with `firtool` under emulation (slower to build
  SystemVerilog).

Two ways to host it:

- **Per-student containers** on students' own machines: `docker run -it --rm -p 8888:8888
  sysprog21/chisel-bootcamp` (README). The notebooks and `riscv/src` live in `/chisel-bootcamp`, so
  to keep work between sessions, run it with a named volume there:
  `docker run -it --rm -p 8888:8888 -v bootcamp-work:/chisel-bootcamp sysprog21/chisel-bootcamp`
  (Docker fills the volume from the image the first time; a newer image's notebooks then do not
  replace the ones in the volume). Or have students download their notebooks and `riscv/src`.
- **JupyterHub** with DockerSpawner: [`docs/jupyterhub/`](jupyterhub/) has a `Dockerfile` that adds
  the hub's single-user server to the image (`docker build --build-arg JUPYTERHUB_VERSION=<the
  hub's version> -t bootcamp-hub docs/jupyterhub`), the settings it adds to the server
  configuration (the image's own is for one person on a laptop: no cross-site request check, any
  origin), and the spawner settings to add to `jupyterhub_config.py`: the image, 4 GB and 2 CPUs
  per user, and a named volume per user on `/chisel-bootcamp`.
