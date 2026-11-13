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

Cost in the Docker image (measured with sbt 1.13.1 and Chisel 7.16.0): +140 MB (961 MB to
1.1 GB): the sbt launcher (4 MB, without the `sbtn` native clients), sbt's runtime and Scala 2.12 in
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
core, which reports the cause on its retire port. So does an access (or a fetch) outside the RAM,
until the bus and its memory-mapped devices arrive (Module 5.11). `FENCE` is a no-op. An
instruction that halts the core retires without changing any state: a jump to a misaligned target
does not write its link register. An access that is both misaligned and outside the RAM halts as
misaligned, and a fetch outside the RAM retires with instruction word 0; the cores must report the
same, since their retire records are compared with the golden model's.

## Exercises and solutions

Students work in `src/`: a module that is an exercise compiles but contains `???` where the
student's code goes, so its test fails with `NotImplementedError` until the exercise is done.
The reference solutions live in `solutions/`, with the same paths as `src/` (for example
`solutions/main/scala/riscv/Alu.scala`); a solution file replaces the scaffold file of the same
name. Both are in this repository, so contributors keep them in sync, but `.dockerignore` keeps
`solutions/` out of the Docker image that students use. The Module 5 notebooks are to show the
solutions in hidden "Solution" boxes (`source/custom.js`), as the other modules do.

`tools/check-solutions.sh` checks both: the scaffold compiles, and with `solutions/` copied over
`src/` every test passes. CI runs it in the image with `solutions/` mounted. A course that wants
the solutions private can move `solutions/` to a private repository and run the same script with
`SOLUTIONS=/path/to/solutions`.

## Using the project from notebooks

`source/load-riscv.sc` compiles the project with sbt when `build.sbt`, `project/` or `src/main/`
changed since the last compilation (it compares a list of paths, sizes and times, so deleted files
count too) and adds `target/scala-2.13/classes` to the notebook's classpath, so a notebook can
`import riscv._` and use the modules in `src/` (as written by the student). A running kernel keeps
the classes it has loaded, so after editing `src/`, restart the kernel. There is one source
tree; the notebooks do not copy code. Ammonite's `import $file` was not used: the sources have
`package` declarations and need the Chisel compiler plugin, which sbt already handles.

## Test programs and the assembler

The programs the cores run are RV32I assembly, assembled by `riscv.Asm` (`src/main/scala/riscv/Asm.scala`),
an assembler written in Scala as part of the project, in the spirit of the one built into the
[Ripes](https://github.com/mortbopet/Ripes) simulator. There is no RISC-V compiler in the Docker
image: a bare-metal GCC with binutils, cut down to RV32I, added about 80 MB, and the course only needs
assembly. The notebooks call the assembler directly (`Asm("...")` returns the words), and it is
small enough for students to read.

`Asm` reads the GNU syntax: the `.text`, `.data` and `.bss` sections, labels and numeric local labels
(`1b`, `1f`), the data and alignment directives, `.equ`, `%hi`/`%lo`/`%pcrel_hi`/`%pcrel_lo`, and
the RV32I pseudo-instructions (`li` of any 32-bit value, `la`, `call`, `tail`, `ret`, branches
against zero, ...); its scaladoc has the full list. It lays a program out the way the cores see
memory: `.text` from `0x8000_0000`, where the core starts (and where riscv-tests start too), then
`.data` from the next word (or the next multiple of its largest `.align`), then `.bss`, which is
not in the image and which nothing clears.
A program sets up its own stack pointer if it needs one (the RAM is 64 KB, so `li sp, 0x80010000`)
and ends with `ECALL`, leaving its result in `a0`.

`programs/src/*.S` are the course's programs and `programs/hex/<name>.hex` their memory images, one
little-endian 32-bit word per line for `$readmemh`. After editing a program, regenerate its image:

```
sbt "runMain riscv.Asm programs/src/fact.S programs/hex/fact.hex"
```

`AsmSpec` fails if an image does not match its source. The committed images were produced by GNU as
and ld (2.44, `-march=rv32i -mno-relax`, with the same layout), so the test also checks `Asm` against
GNU on whole programs; it compares single instructions and every directive and pseudo-instruction
with GNU output too, and assembles each of the 40 instructions.

Without a C compiler the course has no C programs. A student who wants one can install a bare-metal
RISC-V GCC (`-march=rv32i -mabi=ilp32 -nostdlib`, linked at `0x8000_0000` with `.text` first) and
convert its output with `objcopy -O binary` and `hexdump -v -e '1/4 "%08x\n"'`.

## The golden model

`riscv.IsaSim` (`src/main/scala/riscv/IsaSim.scala`) interprets RV32I one instruction at a time and
reports each as a `Retire` record: PC, instruction, next PC, register write, memory access and halt
cause, the fields of the cores' RVFI-style retire port. Tests compare a core's retire stream with
it, not only the final state. It is a teaching oracle written together with the course (it uses
`Rv32i.decode`), not an independent reference; the riscv-tests suite (Module 5.5) is the
independent check.

`riscv.RandomProgram(seed)` writes a random RV32I program in assembly that halts by construction:
branches and jumps only go forward, loads and stores stay in a 1 KB data area based on `s11`. A
program that exposes a difference can be saved as a `.S` file and kept as a regression test.

## Memory interface and the single-cycle core

The cores reach memory through `MemPort` (`src/main/scala/riscv/Memory.scala`): a `Decoupled`
request (word address, write data, byte strobes) and a `Valid` response (data, error for an
address outside the memory). The RAM (`Ram`, 64 KB at `0x8000_0000`, constants in `object Ram`
that `IsaSim` shares) answers in the same cycle on an instruction and a data port, and has a load
port through which testbenches write programs while reset is held. It has a single write port:
firtool drops the write masks of a memory that also has a write port without a mask (a constant
all-ones mask counts as none), so a masked write would write every byte (see 2.8).

`SingleCycleCore` retires one instruction per cycle and depends on that same-cycle answer (it
ignores `ready`); a bus that answers later needs a core that tracks its requests. It reports each
retired instruction on a `Valid(RetireInfo)` port with the fields of `IsaSim`'s `Retire`, and
`CoSim` runs a program on a core with its RAM (any `RiscvSystem`: the load, retire, halted and
debug ports of `SystemIO`) and on `IsaSim`, and returns the first difference. The tests run
directed programs (each feature, every halt cause), the committed programs, random programs, and
`programs/regress/*.S`, programs that once exposed a bug (`RegressSpec`), through `CoSim`. Reset
does not clear the RAM or the registers, so a program may read only what it wrote, or its image.

## The official tests

`programs/riscv-tests/rv32ui/` holds the rv32ui tests of
[riscv-tests](https://github.com/riscv-software-src/riscv-tests) (BSD license, in
`programs/riscv-tests/LICENSE`) at a pinned revision, already expanded: they are written with C
preprocessor macros, and the image has no compiler. `tools/import-riscv-tests.py` (run where git and
`cpp` are available; CI runs it with `--check`) expands them against `programs/riscv-tests/riscv_test.h`,
a test environment without CSRs: a test starts at `_start`, the first word of `.text`, and ends with
`ECALL`, leaving `a0` = 0 if it passed, or (failing case << 1) | 1. `riscv.Asm` assembles the
expanded files; for all of them its output is identical to GNU as and ld 2.44 with the same layout.
`fence_i` (Zifencei) and `ma_data` (misaligned accesses, which halt the cores) are left out
(`programs/riscv-tests/EXCLUDED`). `ComplianceSpec` runs the others on `IsaSim` and on the
single-cycle core (through `CoSim`, so each retired instruction is compared too).

## The five-stage pipeline

`PipelinedCore` (`src/main/scala/riscv/PipelinedCore.scala`) runs IF, ID, EX, MEM and WB, with
stage registers made by `Stage` (a `Valid` register with an enable and a flush). Jumps and branches
are resolved in EX, and the two younger instructions are flushed when one is taken; an instruction
that halts the core flushes all younger ones when it is in MEM, the last stage that finds a halt
cause, so none of them changes memory or registers. Its register reads see a register that WB
writes in the same cycle (written first, read second), but it handles no other data hazard:
programs for it are assembled with `Asm.padded(source, 2)`, two nops after every instruction.
`PipelineFlush` (which stage registers to flush) is the exercise. It shares `Core`, `CoreSystem`
and `CoSim` with the single-cycle core; padded riscv-tests no longer fit their branch range, so
the official tests run on the pipeline once it handles hazards (Module 5.7).
