# The processor on an FPGA board

`Ulx3sTop` (`src/main/scala/riscv/Board.scala`) runs the system of Module 5.8 (the forwarding core,
the bus, a 4 KB RAM and the UART) on a [ULX3S](https://github.com/emard/ulx3s) board, whose Lattice
ECP5 FPGA the open tools support from synthesis to bitstream. It copies a program into the RAM when
the FPGA starts (and on button F1), runs it, and sends what it prints to the board's USB serial
port at 115200 baud; LED 0 lights when the program is loaded, LED 1 when it has halted.

From `riscv/`:

```
fpga/ulx3s.sh hello 12k                      # SystemVerilog, synthesis, place and route, bitstream
openFPGALoader -b ulx3s fpga/build/Ulx3sTop.bit
screen /dev/ttyUSB0 115200                   # or any serial terminal: "Hello, world!"
```

The second argument is the size of the board's FPGA (`12k`, `25k`, `45k` or `85k`). The script
needs sbt, Yosys, nextpnr-ecp5 and ecppack (Project Trellis), for example from the
[OSS CAD Suite](https://github.com/YosysHQ/oss-cad-suite-build), or YoWASP's builds:
`pip install yowasp-yosys yowasp-nextpnr-ecp5`. The bootcamp's Docker image has Yosys only (and
no USB access to a board), so there the script stops at once, saying that nextpnr-ecp5 is missing:
run it on your machine. In the image, `sbt "runMain riscv.Ulx3s"` and Yosys go as far as
synthesis, as Module 5.10 does.

`ulx3s.lpf` gives the pins of `Ulx3sTop`'s ports, taken from the board's published constraints.
A program must fit in the 4 KB RAM and keep its stack there. `hello.S` does; `fact.S` does not run
on the board: its stack is at the top of the 64 KB of the simulations, outside the board's RAM, so
its first store halts the core with a bad address. LEDs 2-7 show the 6 low
bits of a0, so a program that does not print shows its result there.

With the hello program and `12k`, nextpnr-ecp5 0.11 placed and routed the design with room to
spare (4 block RAMs) and reported a maximum clock of about 48 MHz, above the board's 25 MHz.
nextpnr's `--12k` models the die of the 25F (it reports 56 block RAMs, where the 12F has 32), so
this shows the fit and timing on that die; the design uses few enough resources to fit a 12F as
well, but its timing on a 12F has not been checked. This was checked with YoWASP's tools; it has
not been run on a board for this course.
