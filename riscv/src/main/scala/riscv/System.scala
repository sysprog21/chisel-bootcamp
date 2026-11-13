package riscv

import chisel3._
import chisel3.util._

/** What a testbench sees of a core with its RAM: the load port (write the program while reset is
  * held), the retire port (at most one instruction per cycle, in program order; the instruction
  * that halts the core retires too), whether the core has halted, and the debug port of its
  * registers. Reset clears neither the RAM nor the registers.
  */
class SystemIO extends Bundle {
  val load   = Input(Valid(new LoadPort))
  val retire = Output(Valid(new RetireInfo))
  val halted = Output(Bool())
  val dbg    = new DebugPort
}

/** A core with its RAM, as `CoSim` drives it: each core of the course has one (the single-cycle
  * core's is [[SingleCycleSystem]]).
  */
abstract class RiscvSystem extends Module {
  val io = IO(new SystemIO)
}

/** The ports of a core: instruction and data memory, the retire port, whether it has halted, and
  * the debug port of its registers.
  */
class CoreIO extends Bundle {
  val imem   = new MemPort
  val dmem   = new MemPort
  val retire = Output(Valid(new RetireInfo))
  val halted = Output(Bool())
  val dbg    = new DebugPort
}

/** A core of the course (single-cycle, pipelined): it starts at its reset pc (`Ram.base` by default). */
abstract class Core extends Module {
  val io = IO(new CoreIO)
}

/** A core with the RAM, for tests and notebooks. */
class CoreSystem(gen: => Core) extends RiscvSystem {
  val core = Module(gen)
  val ram  = Module(new Ram)
  ram.io.imem <> core.io.imem
  ram.io.dmem <> core.io.dmem
  ram.io.load := io.load
  io.retire   := core.io.retire
  io.halted   := core.io.halted
  io.dbg <> core.io.dbg
}
