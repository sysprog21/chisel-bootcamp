package riscv

import chisel3._
import chisel3.util._

/** A pipeline register: at each clock edge (when `enable`) it takes `next`; when `flush`ed it takes
  * a bubble instead (`valid` false). It starts empty.
  */
object Stage {
  def apply[T <: Data](next: Valid[T], flush: Bool = false.B, enable: Bool = true.B): Valid[T] = {
    val r      = RegInit(0.U.asTypeOf(chiselTypeOf(next)))
    when(enable)(r      := next)
    when(flush)(r.valid := false.B)
    r
  }
}

/** What IF hands to ID: the fetched instruction (0 if the fetch failed). */
class IfId extends Bundle {
  val pc         = UInt(32.W)
  val inst       = UInt(32.W)
  val fetchError = Bool()
}

/** What ID hands to EX: the decoded instruction, its registers and immediate, and the reason it
  * halts the core, if decoding found one.
  */
class IdEx extends Bundle {
  val pc      = UInt(32.W)
  val inst    = UInt(32.W)
  val ctrl    = new Control
  val rs1Data = UInt(32.W)
  val rs2Data = UInt(32.W)
  val imm     = UInt(32.W)
  val cause   = HaltCause()
}

/** What EX hands to MEM: the ALU's result (a value, an address or a target), the value a store
  * writes, the next pc, and the halt cause so far.
  */
class ExMem extends Bundle {
  val pc        = UInt(32.W)
  val inst      = UInt(32.W)
  val ctrl      = new Control
  val result    = UInt(32.W)
  val storeData = UInt(32.W)
  val nextPc    = UInt(32.W)
  val cause     = HaltCause()
}

/** A five-stage pipelined RV32I core: fetch (IF), decode and register read (ID), execute (EX),
  * memory (MEM), write back (WB), one instruction in each. Jumps and branches are resolved in EX
  * (fetch goes on at pc + 4 meanwhile, and the two instructions fetched after a jump or a taken
  * branch are flushed). An instruction that halts the core flushes all younger ones when it is in
  * MEM, the last stage that finds a halt cause, before they reach MEM or WB. It handles no data
  * hazards: an instruction reads its registers in ID, and sees a result once its producer is in WB
  * (the register file is written before it is read in a cycle), so an instruction that uses a
  * result must come 3 instructions after the one that computes it (with 2 others, or nops, between).
  * The memory must answer in the same cycle.
  */
class PipelinedCore(resetPc: Long = Ram.base) extends Core {
  val stopped                            = RegInit(false.B) // a halting instruction has passed MEM: fetch no more
  val halted                             = RegInit(false.B)
  val flush                              = Module(new PipelineFlush)
  def is(c: Control, k: Kind.Type): Bool = c.legal && c.kind === k

  // ---- IF
  val pc       = RegInit(resetPc.U(32.W))
  val fetching = !reset.asBool && !stopped
  io.imem.req.valid      := fetching
  io.imem.req.bits.addr  := pc
  io.imem.req.bits.wdata := 0.U
  io.imem.req.bits.wstrb := 0.U
  val fetched = Wire(Valid(new IfId))
  fetched.valid           := fetching && io.imem.resp.valid
  fetched.bits.pc         := pc
  fetched.bits.fetchError := io.imem.resp.bits.error
  fetched.bits.inst       := Mux(io.imem.resp.bits.error, 0.U, io.imem.resp.bits.rdata)
  val ifId = Stage(fetched, flush.io.flushIfId)

  // ---- ID
  val decoder = Module(new Decoder)
  decoder.io.inst := ifId.bits.inst
  val dc   = decoder.io.ctrl
  val regs = Module(new RegFile)
  regs.io.rs1     := ifId.bits.inst(19, 15)
  regs.io.rs2     := ifId.bits.inst(24, 20)
  regs.io.dbgAddr := io.dbg.addr
  io.dbg.data     := regs.io.dbgData
  val immGen = Module(new ImmGen)
  immGen.io.inst   := ifId.bits.inst
  immGen.io.format := dc.immFormat
  val decoded = Wire(Valid(new IdEx))
  decoded.valid     := ifId.valid
  decoded.bits.pc   := ifId.bits.pc
  decoded.bits.inst := ifId.bits.inst
  decoded.bits.ctrl := dc
  // The register file is written in the first half of a cycle and read in the second (as in the
  // classic five-stage pipeline): a register that WB writes in this cycle reads as its new value.
  val wbRd                          = Wire(UInt(5.W))
  val wbValue                       = Wire(UInt(32.W))
  def read(index: UInt, data: UInt) = Mux(wbRd =/= 0.U && wbRd === index, wbValue, data)
  decoded.bits.rs1Data := read(ifId.bits.inst(19, 15), regs.io.rs1Data)
  decoded.bits.rs2Data := read(ifId.bits.inst(24, 20), regs.io.rs2Data)
  decoded.bits.imm     := immGen.io.imm
  decoded.bits.cause   := MuxCase(
    HaltCause.Running,
    Seq(
      ifId.bits.fetchError -> HaltCause.BadAddress,
      !dc.legal            -> HaltCause.Illegal,
      is(dc, Kind.Ecall)   -> HaltCause.Ecall,
      is(dc, Kind.Ebreak)  -> HaltCause.Ebreak
    )
  )
  val idEx = Stage(decoded, flush.io.flushIdEx)

  // ---- EX
  val e   = idEx.bits
  val alu = Module(new Alu)
  alu.io.op := e.ctrl.aluOp
  alu.io.a  := MuxLookup(e.ctrl.aSel, e.rs1Data)(Seq(ASel.Pc -> e.pc, ASel.Zero -> 0.U))
  alu.io.b  := Mux(e.ctrl.bSel === BSel.Imm, e.imm, e.rs2Data)
  val branchUnit = Module(new BranchUnit)
  branchUnit.io.funct3 := e.inst(14, 12)
  branchUnit.io.a      := e.rs1Data
  branchUnit.io.b      := e.rs2Data
  val jalr      = is(e.ctrl, Kind.Jalr)
  val jumps     = is(e.ctrl, Kind.Jal) || jalr || (is(e.ctrl, Kind.Branch) && branchUnit.io.taken)
  val target    = Mux(jalr, alu.io.out(31, 1) ## 0.U(1.W), alu.io.out)
  val badTarget = jumps && target(1, 0) =/= 0.U
  val causeEx   = Mux(e.cause === HaltCause.Running && badTarget, HaltCause.Misaligned, e.cause)
  val redirect  = idEx.valid && jumps && causeEx === HaltCause.Running
  val executed  = Wire(Valid(new ExMem))
  executed.valid          := idEx.valid
  executed.bits.pc        := e.pc
  executed.bits.inst      := e.inst
  executed.bits.ctrl      := e.ctrl
  executed.bits.result    := alu.io.out
  executed.bits.storeData := e.rs2Data
  executed.bits.nextPc    := Mux(jumps, target, e.pc + 4.U)
  executed.bits.cause     := causeEx
  val exMem = Stage(executed, flush.io.flushExMem)

  // ---- MEM
  val m          = exMem.bits
  val load       = is(m.ctrl, Kind.Load)
  val store      = is(m.ctrl, Kind.Store)
  val funct3     = m.inst(14, 12)
  val size       = funct3(1, 0)
  val bytes      = 1.U(3.W) << size
  val offset     = m.result(1, 0)
  val shamt      = offset ## 0.U(3.W)
  val misaligned = (load || store) && (offset & (bytes - 1.U)(1, 0)) =/= 0.U
  val access     = exMem.valid && (load || store) && !misaligned && m.cause === HaltCause.Running
  val strobe     = ((1.U(5.W) << bytes) - 1.U)(3, 0)
  val sizeMask   = FillInterleaved(8, strobe)
  io.dmem.req.valid      := access
  io.dmem.req.bits.addr  := m.result(31, 2) ## 0.U(2.W)
  io.dmem.req.bits.wdata := (m.storeData << shamt)(31, 0)
  io.dmem.req.bits.wstrb := Mux(store, (strobe << offset)(3, 0), 0.U)
  val extend = Module(new LoadExtend)
  extend.io.funct3 := funct3
  extend.io.offset := offset
  extend.io.rdata  := io.dmem.resp.bits.rdata
  val causeMem = MuxCase(
    HaltCause.Running,
    Seq(
      (m.cause =/= HaltCause.Running)     -> m.cause,
      misaligned                          -> HaltCause.Misaligned,
      (access && io.dmem.resp.bits.error) -> HaltCause.BadAddress
    )
  )
  val halts      = causeMem =/= HaltCause.Running
  val linkPc     = m.pc + 4.U
  val link       = is(m.ctrl, Kind.Jal) || is(m.ctrl, Kind.Jalr) // jal and jalr write pc + 4
  val rd         = m.inst(11, 7)
  val writesRd   = (is(m.ctrl, Kind.Alu) || load || link) && !halts && rd =/= 0.U
  val writeValue = Mux(load, extend.io.value, Mux(link, linkPc, m.result))
  val retiring   = Wire(Valid(new RetireInfo))
  val r          = retiring.bits
  retiring.valid   := exMem.valid
  r.pc             := m.pc
  r.inst           := m.inst
  r.nextPc         := Mux(halts, m.pc, m.nextPc)
  r.rd             := Mux(writesRd, rd, 0.U)
  r.rdValue        := Mux(writesRd, writeValue, 0.U)
  r.mem.valid      := access && !halts
  r.mem.bits.addr  := m.result
  r.mem.bits.write := store
  r.mem.bits.bytes := bytes
  r.mem.bits.data  := Mux(store, m.storeData, io.dmem.resp.bits.rdata >> shamt) & sizeMask
  r.halt           := causeMem
  val haltInMem = exMem.valid && halts
  when(haltInMem)(stopped := true.B)
  val memWb     = Stage(retiring)

  // ---- WB
  wbRd          := Mux(memWb.valid, memWb.bits.rd, 0.U) // rd is 0 if nothing is written
  wbValue       := memWb.bits.rdValue
  regs.io.wen   := wbRd =/= 0.U
  regs.io.rd    := wbRd
  regs.io.wdata := wbValue
  io.retire     := memWb
  when(memWb.valid && memWb.bits.halt =/= HaltCause.Running)(halted := true.B)
  io.halted     := halted

  // ---- the next pc, and the flushes
  flush.io.redirect := redirect
  flush.io.halt     := haltInMem
  when(redirect)(pc := target).elsewhen(fetched.valid)(pc := pc + 4.U)
}

/** The pipelined core with its RAM, for tests and notebooks. */
class PipelinedSystem extends CoreSystem(new PipelinedCore)
