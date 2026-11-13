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
