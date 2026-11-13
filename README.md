[![Binder](https://mybinder.org/badge_logo.svg)](https://mybinder.org/v2/gh/sysprog21/chisel-bootcamp/HEAD)

# Chisel Bootcamp

Elevate the level of your hardware design from instances to generators!
This bootcamp teaches you Chisel, a Berkeley hardware construction DSL written in Scala.
It teaches you Scala along the way, and it frames the learning of Chisel on the idea of *hardware generators*.

## What you'll learn

- Why hardware designs are better expressed as generators, not instances
- Basics and some advanced features of Scala, a modern programming language
- Basics and some advanced features of [Chisel 7](https://github.com/chipsalliance/chisel), a hardware description language embedded in Scala
- How to write unit tests for Chisel designs with ChiselSim and Verilator
- How Chisel designs are compiled to SystemVerilog with CIRCT's `firtool`, and how to read the FIRRTL in between

## Prerequisites

- Familiarity with Verilog, VHDL, or at least some digital hardware design
- Programming experience in with a "high-level" language, be it in Python, Java, C++, etc.
- An earnest desire to learn

## Getting Started

### Quick Start with Docker

The fastest way to get started is using the prebuilt Docker image that supports both x86-64 and Arm64 (there is no native Linux/Arm64 build of CIRCT's `firtool`, so on Arm64 it runs under qemu-user emulation; everything else is native):

Using Docker:
```bash
docker run -it --rm -p 8888:8888 sysprog21/chisel-bootcamp
```

Using nerdctl:
```bash
nerdctl run -it --rm -p 8888:8888 sysprog21/chisel-bootcamp
```

Then open the provided URL (starting with http://127.0.0.1:8888) in your browser.

Image tags: `latest` follows the `dev` branch, `chisel-<version>` (for example `chisel-7.16.0`)
is the newest image built for that Chisel release, and `sha-<commit>` (the first 7 hex digits
of a `dev` commit; a commit superseded by a newer push while its build was queued has none)
pins one exact build.

### Alternative Options

- Try it online [HERE](https://mybinder.org/v2/gh/sysprog21/chisel-bootcamp/HEAD)! No local installation required!
- For local installation without Docker, [look at installation instructions here](Install.md).

## Outline

The bootcamp is divided into modules, which are further subdivided. This README serves as
*Module 0*, an introduction and motivation. The times are rough estimates for a first pass,
exercises included, from the length of each notebook; they vary a lot with your background.

| Notebook | Topic | Time |
|---|---|---|
| [0_demo](0_demo.ipynb) | A preview of what you will learn (not part of the course) | 15 min |
| **Module 1: Scala** | | |
| [1_intro_to_scala](1_intro_to_scala.ipynb) | Enough Scala to start writing Chisel; more comes along the way | 45 min |
| **Module 2: Chisel** | | |
| [2.1_first_module](2.1_first_module.ipynb) | A hardware example, broken down | 35 min |
| [2.2_comb_logic](2.2_comb_logic.ipynb) | Combinational logic | 55 min |
| [2.3_control_flow](2.3_control_flow.ipynb) | Software and hardware control flow | 75 min |
| [2.4_sequential_logic](2.4_sequential_logic.ipynb) | Registers, and the kinds of reset | 55 min |
| [2.5_exercise](2.5_exercise.ipynb) | Putting it together: an FIR filter | 25 min |
| [2.6_chiselsim](2.6_chiselsim.ipynb) | Testing with ChiselSim and Verilator, waveforms | 50 min |
| [2.7_enums_and_bundles](2.7_enums_and_bundles.ipynb) | Enums, bit-pattern decoders, bundles and vectors | 65 min |
| [2.8_memories](2.8_memories.ipynb) | Memories, write masks, a register file | 45 min |
| **Module 3: generators** | | |
| [3.1_parameters](3.1_parameters.ipynb) | Parameters | 45 min |
| [3.2_collections](3.2_collections.ipynb) | Collections | 40 min |
| [3.2_interlude](3.2_interlude.ipynb) | The Chisel standard library | 20 min |
| [3.3_higher-order_functions](3.3_higher-order_functions.ipynb) | Higher-order functions | 35 min |
| [3.4_functional_programming](3.4_functional_programming.ipynb) | Functional programming | 75 min |
| [3.5_object_oriented_programming](3.5_object_oriented_programming.ipynb) | Object-oriented programming | 25 min |
| [3.6_types](3.6_types.ipynb) | Types | 90 min |
| **Module 4: under the hood** | | |
| [4.1_firrtl_ast](4.1_firrtl_ast.ipynb) | FIRRTL, and how `firtool` turns it into SystemVerilog | 40 min |
| [4.2_firrtl_ast_traversal](4.2_firrtl_ast_traversal.ipynb) | Walking a FIRRTL circuit | 45 min |
| [4.3_firrtl_common_idioms](4.3_firrtl_common_idioms.ipynb) | Common idioms of passes: collecting, adding, deleting | 40 min |
| [4.4_firrtl_add_ops_per_module](4.4_firrtl_add_ops_per_module.ipynb) | A worked example: counting adders per module | 35 min |
| **Module 5: a RISC-V processor** | | |
| [5.1_riscv_isa](5.1_riscv_isa.ipynb) | The RV32I instruction set, a decoder and an assembler in Scala | 55 min |
| [5.2_alu_and_regfile](5.2_alu_and_regfile.ipynb) | The ALU, the immediate generator and the register file | 45 min |
| [5.3_decoder](5.3_decoder.ipynb) | A table-driven decoder | 45 min |
| [5.4_single_cycle](5.4_single_cycle.ipynb) | A single-cycle RV32I processor, checked against a golden model | 70 min |
| [5.5_compliance](5.5_compliance.ipynb) | The official RISC-V tests on the processor | 15 min |
| [5.6_pipeline](5.6_pipeline.ipynb) | A five-stage pipeline: stages, flushes, data hazards | 45 min |
| [5.7_hazards](5.7_hazards.ipynb) | Hazards: forwarding, stalls, the official tests on the pipeline | 40 min |
| [5.8_bus_and_mmio](5.8_bus_and_mmio.ipynb) | A bus, memory-mapped I/O and a UART: Hello, world | 45 min |

Module 5 builds the processor in an sbt project under `riscv/` (see
[Install.md](Install.md#the-riscv-sbt-project)): you write parts of it in Scala files there, and the
notebooks load the project.

By the end, you will be able to read and understand most of the
[Chisel code base](https://github.com/chipsalliance/chisel), begin using
[Rocket Chip](https://github.com/chipsalliance/rocket-chip), and build and test a processor of your
own. This tutorial *does not* yet cover backend flows for FPGA or ASIC processes, or analog circuits.

### Which path should I take?

- **New to Scala:** take Module 1 in full, then Modules 2 and 3 in order. Module 4 is optional.
- **Verilog or VHDL designer:** read the sections of Module 1 tagged *Required* (enough to read
  Scala), take Module 2 (2.6 on testing and 2.7-2.8 differ most from what you know), then Module 3
  for what Verilog cannot do: generators.
- **Here for the RISC-V processor:** Module 1 (at least its *Required* sections), what Module 5
  assumes (below), then Module 5 in order. Module 4 and the rest of Module 3 are optional for it.

Module 5 assumes Module 2 (2.6-2.8 especially: testing, decoders and enums, memories), 3.1
(`match`, `Option`, case classes), 3.2_interlude (`Decoupled`) and 3.3 (the collection methods),
and knowing what a processor's datapath is (a computer architecture course, or a book such as
Patterson and Hennessy's *Computer Organization and Design*); it explains RV32I itself.

## Motivation
All hardware description languages support writing single instances.
However, writing instances is tedious.
Why make the same mistakes writing a slightly modified version of something somebody else has likely already designed?
Verilog supports limited parameterization, such as bitwidths and generate statements, but this only gets you so far.
If we can't write a Verilog generator, we need to write a new instance, thus doubling our code size.
As a better option, we should write one program that generates both hardware instances, which would reduce our code size and make tedious things easier.
These programs are called generators.

Ideally, we want our generators to be (1) composable, (2) powerful, and (3) enable fine-grained control over the generated design.
Error checking is necessary to make sure a composition is legal; without it, debugging is difficult.
This requires a generator language to understand the semantics of the design (to know what’s legal and what’s not).
Also, the generator should not be overly verbose!
We want the generator program to concisely express many different designs, without rewriting it in if statements for each instance.
Finally, it should be a zero-cost abstraction.
Hardware design performance is very sensitive to small changes, and because of that, you need to be able to exactly specify the microarchitecture.
Generators are very different than high-level-synthesis (HLS).

The benefits of Chisel are in how you use it, not in the language itself.
If you decide to write instances instead of generators, you will see fewer advantages of Chisel over Verilog.
But if you take the time to learn how to write generators, then the power of Chisel will become apparent and you will realize you can never go back to writing Verilog.
Learning to write generators is difficult, but we hope this tutorial will pave the way for you to become a better hardware designer, programmer, and thinker!

## Contributors

The bootcamp was written at UC Berkeley by the people below. This fork,
[sysprog21/chisel-bootcamp](https://github.com/sysprog21/chisel-bootcamp), moved it to Chisel 7,
ChiselSim and CIRCT's `firtool`, rewrote the testing notebook for ChiselSim, added the notebooks on
enums, decoders and memories, and Module 5 with its RISC-V project.

- Stevo Bailey ([stevo@berkeley.edu](mailto:stevo@berkeley.edu))
- Adam Izraelevitz ([adamiz@berkeley.edu](mailto:azidar@berkeley.edu))
- Richard Lin ([richard.lin@berkeley.edu](mailto:edwardw@berkeley.edu))
- Chick Markley ([chick@berkeley.edu](mailto:chick@berkeley.edu))
- Paul Rigge ([rigge@berkeley.edu](mailto:rigge@berkeley.edu))
- Edward Wang ([edwardw@berkeley.edu](mailto:edwardw@berkeley.edu))
