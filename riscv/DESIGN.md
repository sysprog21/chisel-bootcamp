# Design of the Module 5 RISC-V project

Decisions for the processor built in Module 5 and for this sbt project. The notebooks of Module 5
refer to this file.

## Build tool: sbt

The project is built with [sbt](https://www.scala-sbt.org/) 1.x (pinned in
`project/build.properties`), not Mill, because:

- it is the build tool most Chisel material for learners uses (the chisel-template project,
  Chipyard, Rocket Chip's users), so what students learn here carries over;
- its dependencies resolve through coursier into the same `COURSIER_CACHE` the notebooks use, so
  the Docker image shares one preloaded cache, and sbt runs on the image's JRE;
- sbt 1.x keeps the build definition in Scala 2.12, independent of the project's Scala 2.13;
  sbt 2.0 (Scala 3 build definitions) can follow once the course material settles.

Cost in the Docker image (measured with sbt 1.13.1 and Chisel 7.16.0): +140 MB (961 MB to 1.1 GB): the sbt
launcher (4 MB, without the `sbtn` native clients), sbt's runtime and Scala 2.12 in
`/opt/sbt-home` (73 MB), and the project's extra dependencies in `/coursier_cache` (58 MB).
The warm-up `sbt update Test/compile` adds about a minute to a native image build; a complete
Arm64 cross-build under qemu took 310 s (see the Dockerfile for the qemu workaround).

## ISA: RV32I only, no CSRs

RV32I, pinned to "The RISC-V Instruction Set Manual, Volume I: Unprivileged Architecture",
version 20240411. `Instructions.all` lists its 40 instructions (FENCE.I belongs to Zifencei).
Nothing else: no CSRs (Zicsr), hence no privileged architecture, traps or interrupts, and no
extensions (M, A, C, F, ...). The CSR instructions (SYSTEM opcode, funct3 != 0) are illegal.

Without CSRs there is no trap handler, so `ECALL`, `EBREAK`, an illegal instruction and a
misaligned access (load/store, or a branch/jump target that is not 4-byte aligned) halt the
core, which reports the cause on its retire port. `FENCE` is a no-op.
