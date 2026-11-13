package riscv

import chisel3._

/** The 32 integer registers (the one of Module 2.8): two combinational read ports, one write port
  * (written at the clock edge), and a debug read port for tests. `x0` reads 0 and ignores writes.
  * The other registers start with unknown values, as in hardware.
  */
class RegFile extends Module {
  val io = IO(new Bundle {
    val rs1     = Input(UInt(5.W))
    val rs2     = Input(UInt(5.W))
    val rs1Data = Output(UInt(32.W))
    val rs2Data = Output(UInt(32.W))
    val wen     = Input(Bool())
    val rd      = Input(UInt(5.W))
    val wdata   = Input(UInt(32.W))
    val dbgAddr = Input(UInt(5.W))
    val dbgData = Output(UInt(32.W))
  })
  val regs = Mem(32, UInt(32.W))

  def read(addr: UInt): UInt = Mux(addr === 0.U, 0.U, regs(addr))
  io.rs1Data := read(io.rs1)
  io.rs2Data := read(io.rs2)
  io.dbgData := read(io.dbgAddr)
  when(io.wen && io.rd =/= 0.U) {
    regs.write(io.rd, io.wdata)
  }
}
