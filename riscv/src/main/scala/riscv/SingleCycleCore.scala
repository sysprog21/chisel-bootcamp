package riscv

import chisel3._
import chisel3.util._

/** Why a core halted (see DESIGN.md); `Running` while it has not. */
object HaltCause extends ChiselEnum {
  val Running, Ecall, Ebreak, Illegal, Misaligned, BadAddress = Value
}

/** What a core reports for each retired instruction (the RVFI-style retire port), with the same
  * meaning as the golden model's `Retire` record: `rd` is 0 if no register is written; `mem` is
  * the access (byte address, size in bytes, the bytes read or written, zero-extended); an
  * instruction that halts the core reports its cause, `nextPc = pc`, and nothing written.
  */
class RetireInfo extends Bundle {
  val pc      = UInt(32.W)
  val inst    = UInt(32.W)
  val nextPc  = UInt(32.W)
  val rd      = UInt(5.W)
  val rdValue = UInt(32.W)
  val mem     = Valid(new Bundle {
    val addr  = UInt(32.W)
    val write = Bool()
    val bytes = UInt(3.W)
    val data  = UInt(32.W)
  })
  val halt = HaltCause()
}

/** A single-cycle RV32I core: each instruction is fetched, decoded, executed and retired in one
  * clock cycle, through the instruction and data ports, which must answer in the same cycle (see
  * `MemPort`: the core does not track requests). It starts at `resetPc`, and halts as DESIGN.md says.
  */
class SingleCycleCore(resetPc: Long = Ram.base) extends Module {
  val io = IO(new Bundle {
    val imem   = new MemPort
    val dmem   = new MemPort
    val retire = Output(Valid(new RetireInfo))
    val halted = Output(Bool())
    val dbg    = new DebugPort
  })

  val pc     = RegInit(resetPc.U(32.W))
  val halted = RegInit(false.B)
  // the core does nothing while reset is held (a testbench loads the program then) or once halted
  val running = !halted && !reset.asBool

  // fetch
  io.imem.req.valid      := running
  io.imem.req.bits.addr  := pc
  io.imem.req.bits.wdata := 0.U
  io.imem.req.bits.wstrb := 0.U
  val fetched    = io.imem.resp.valid
  val fetchError = io.imem.resp.bits.error
  val inst       = Mux(fetchError, 0.U, io.imem.resp.bits.rdata)

  // decode, and read the registers
  val decoder = Module(new Decoder)
  decoder.io.inst := inst
  val c      = decoder.io.ctrl
  val funct3 = inst(14, 12)
  val rd     = inst(11, 7)
  val regs   = Module(new RegFile)
  regs.io.rs1     := inst(19, 15)
  regs.io.rs2     := inst(24, 20)
  regs.io.dbgAddr := io.dbg.addr
  io.dbg.data     := regs.io.dbgData
  val immGen = Module(new ImmGen)
  immGen.io.inst   := inst
  immGen.io.format := c.immFormat

  // execute: the ALU computes the result, the memory address or the jump/branch target
  val alu = Module(new Alu)
  alu.io.op := c.aluOp
  alu.io.a  := MuxLookup(c.aSel, regs.io.rs1Data)(Seq(ASel.Pc -> pc, ASel.Zero -> 0.U))
  alu.io.b  := Mux(c.bSel === BSel.Imm, immGen.io.imm, regs.io.rs2Data)
  val result = alu.io.out

  def is(k: Kind.Type): Bool           = c.legal && c.kind === k
  val (load, store, branch, jal, jalr) = (is(Kind.Load), is(Kind.Store), is(Kind.Branch), is(Kind.Jal), is(Kind.Jalr))

  // memory: a byte, half-word or word at `result`, which must be aligned to its size
  val size       = funct3(1, 0)                      // 0 byte, 1 half-word, 2 word
  val bytes      = 1.U(3.W) << size
  val offset     = result(1, 0)
  val misaligned = (load || store) && (offset & (bytes - 1.U)(1, 0)) =/= 0.U
  val access     = (load || store) && !misaligned
  val strobe     = ((1.U(5.W) << bytes) - 1.U)(3, 0) // 0001, 0011 or 1111
  val sizeMask   = FillInterleaved(8, strobe)        // the same, one bit per bit
  io.dmem.req.valid     := running && fetched && access
  io.dmem.req.bits.addr := result(31, 2) ## 0.U(2.W)
  val shamt = offset ## 0.U(3.W) // the byte offset in bits
  io.dmem.req.bits.wdata := (regs.io.rs2Data << shamt)(31, 0)
  io.dmem.req.bits.wstrb := Mux(store, (strobe << offset)(3, 0), 0.U)
  val extend = Module(new LoadExtend)
  extend.io.funct3 := funct3
  extend.io.offset := offset
  extend.io.rdata  := io.dmem.resp.bits.rdata

  // the next pc
  val branchUnit = Module(new BranchUnit)
  branchUnit.io.funct3 := funct3
  branchUnit.io.a      := regs.io.rs1Data
  branchUnit.io.b      := regs.io.rs2Data
  val jumps     = jal || jalr || (branch && branchUnit.io.taken)
  val target    = Mux(jalr, result(31, 1) ## 0.U(1.W), result)
  val badTarget = jumps && target(1, 0) =/= 0.U
  val pcPlus4   = pc + 4.U
  val nextPc    = Mux(jumps, target, pcPlus4)

  // why the core halts, if it does (the first that applies, as the golden model checks them)
  val cause = MuxCase(
    HaltCause.Running,
    Seq(
      fetchError                          -> HaltCause.BadAddress,
      !c.legal                            -> HaltCause.Illegal,
      is(Kind.Ecall)                      -> HaltCause.Ecall,
      is(Kind.Ebreak)                     -> HaltCause.Ebreak,
      (misaligned || badTarget)           -> HaltCause.Misaligned,
      (access && io.dmem.resp.bits.error) -> HaltCause.BadAddress
    )
  )
  val halts = cause =/= HaltCause.Running

  // retire: once the instruction (and its data access, if any) has its answers
  val retires    = running && fetched && (!access || io.dmem.resp.valid)
  val writesRd   = (is(Kind.Alu) || load || jal || jalr) && !halts && rd =/= 0.U
  val writeValue = Mux(load, extend.io.value, Mux(jal || jalr, pcPlus4, result))
  regs.io.wen   := retires && writesRd
  regs.io.rd    := rd
  regs.io.wdata := writeValue
  when(retires) {
    when(halts)(halted := true.B).otherwise(pc := nextPc)
  }
  io.halted := halted

  val r = io.retire.bits
  io.retire.valid  := retires
  r.pc             := pc
  r.inst           := inst
  r.nextPc         := Mux(halts, pc, nextPc)
  r.rd             := Mux(writesRd, rd, 0.U)
  r.rdValue        := Mux(writesRd, writeValue, 0.U)
  r.mem.valid      := access && !halts
  r.mem.bits.addr  := result
  r.mem.bits.write := store
  r.mem.bits.bytes := bytes
  r.mem.bits.data  := Mux(store, regs.io.rs2Data, io.dmem.resp.bits.rdata >> shamt) & sizeMask
  r.halt           := cause
}

/** A core with its RAM, for tests and notebooks: the testbench writes the program through `load`
  * (with reset held), then lets the core run.
  */
class SingleCycleSystem extends RiscvSystem {
  val core = Module(new SingleCycleCore)
  val ram  = Module(new Ram)
  ram.io.imem <> core.io.imem
  ram.io.dmem <> core.io.dmem
  ram.io.load := io.load
  io.retire   := core.io.retire
  io.halted   := core.io.halted
  io.dbg <> core.io.dbg
}
