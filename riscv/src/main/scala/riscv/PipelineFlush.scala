package riscv

import chisel3._

/** Which stage registers of the five-stage pipeline take a bubble instead of the instruction
  * coming from the stage before (IF -> IF/ID -> ID -> ID/EX -> EX -> EX/MEM -> MEM -> MEM/WB -> WB):
  *
  *   - `redirect`: the instruction in EX is a jump or a taken branch, so the two instructions
  *     fetched after it (now in IF and ID) are on the wrong path;
  *   - `halt`: the instruction in MEM halts the core, so every younger instruction (in IF, ID and
  *     EX) must not reach MEM or WB, where instructions change memory and registers; fetching stops.
  */
class PipelineFlush extends Module {
  val io = IO(new Bundle {
    val redirect   = Input(Bool())
    val halt       = Input(Bool())
    val flushIfId  = Output(Bool()) // the instruction in IF does not enter ID
    val flushIdEx  = Output(Bool()) // the instruction in ID does not enter EX
    val flushExMem = Output(Bool()) // the instruction in EX does not enter MEM
  })

  io.flushIfId  := ??? // Exercise
  io.flushIdEx  := ??? // Exercise
  io.flushExMem := ??? // Exercise
}
