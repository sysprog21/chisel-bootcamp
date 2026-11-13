package riscv

import chisel3._
import chisel3.util._

/** The five-stage pipeline of [[PipelinedCore]], with its hazards handled in hardware, so programs
  * need no nops:
  *
  *   - data hazards: EX takes an operand from an older instruction still in the pipeline (EX/MEM,
  *     then MEM/WB) instead of the value read in ID (`Forward`); a load's value only exists after
  *     MEM, so an instruction that uses it right after the load waits one cycle in ID (a
  *     load-use stall: IF and ID hold, EX gets a bubble);
  *   - control hazards: as in `PipelinedCore`, fetch goes on at pc + 4 (predict not taken), and a
  *     jump or taken branch in EX flushes the two instructions behind it;
  *   - a data memory that answers late: while the access in MEM waits, the whole pipeline holds,
  *     and WB gets bubbles. The instruction memory must answer in the same cycle (a slow fetch
  *     needs a core that keeps its fetch request across a redirect).
  */
class ForwardingCore(resetPc: Long = Ram.base) extends Core {
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
  // hazards that hold stages (computed below)
  val loadUse = Wire(Bool())         // ID needs the value of the load in EX
  val memWait = Wire(Bool())         // the data access in MEM waits for the memory
  val ifMoves = !loadUse && !memWait // IF passes its instruction on, and fetches the next
  val ifId    = Stage(fetched, flush.io.flushIfId, enable = ifMoves)

  // ---- ID
  val decoder = Module(new Decoder)
  decoder.io.inst := ifId.bits.inst
  val dc    = decoder.io.ctrl
  val regs  = Module(new RegFile)
  val idRs1 = ifId.bits.inst(19, 15)
  val idRs2 = ifId.bits.inst(24, 20)
  regs.io.rs1     := idRs1
  regs.io.rs2     := idRs2
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
  decoded.bits.rs1Data := read(idRs1, regs.io.rs1Data)
  decoded.bits.rs2Data := read(idRs2, regs.io.rs2Data)
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
  // which registers the instruction in ID reads (branches compare both; aSel is the ALU's choice)
  def uses(kinds: Kind.Type*) = kinds.map(is(dc, _)).reduce(_ || _)
  // rs1: branches compare it, and the others that read it give it to the ALU (lui and auipc do not)
  val usesRs1 = uses(Kind.Branch) || (uses(Kind.Alu, Kind.Load, Kind.Store, Kind.Jalr) && dc.aSel === ASel.Rs1)
  // rs2: branches compare it, stores store it, and register-register instructions give it to the ALU
  val usesRs2 = uses(Kind.Branch, Kind.Store) || (uses(Kind.Alu) && dc.bSel === BSel.Rs2)
  val idEx    = Stage(decoded, flush.io.flushIdEx || (loadUse && !memWait), enable = !memWait)

  // ---- EX
  val e = idEx.bits
  // the operands, forwarded from the instructions ahead (wired at the end, once MEM and WB exist)
  val fromExMem                               = Wire(Valid(new Result))
  val fromMemWb                               = Wire(Valid(new Result))
  def forward(rs: UInt, regValue: UInt): UInt = {
    val f = Module(new Forward)
    f.io.rs       := rs
    f.io.regValue := regValue
    f.io.exMem    := fromExMem
    f.io.memWb    := fromMemWb
    f.io.value
  }
  val rs1Data = forward(e.inst(19, 15), e.rs1Data)
  val rs2Data = forward(e.inst(24, 20), e.rs2Data)
  // While the pipeline waits for memory, WB empties: keep the forwarded operands in ID/EX, so that
  // EX still has them when the pipeline moves again.
  when(memWait) {
    idEx.bits.rs1Data := rs1Data
    idEx.bits.rs2Data := rs2Data
  }
  val exRd = e.inst(11, 7)
  loadUse := idEx.valid && is(e.ctrl, Kind.Load) && exRd =/= 0.U && ifId.valid &&
    ((usesRs1 && idRs1 === exRd) || (usesRs2 && idRs2 === exRd))
  val alu = Module(new Alu)
  alu.io.op := e.ctrl.aluOp
  alu.io.a  := MuxLookup(e.ctrl.aSel, rs1Data)(Seq(ASel.Pc -> e.pc, ASel.Zero -> 0.U))
  alu.io.b  := Mux(e.ctrl.bSel === BSel.Imm, e.imm, rs2Data)
  val branchUnit = Module(new BranchUnit)
  branchUnit.io.funct3 := e.inst(14, 12)
  branchUnit.io.a      := rs1Data
  branchUnit.io.b      := rs2Data
  val jalr      = is(e.ctrl, Kind.Jalr)
  val jumps     = is(e.ctrl, Kind.Jal) || jalr || (is(e.ctrl, Kind.Branch) && branchUnit.io.taken)
  val target    = Mux(jalr, alu.io.out(31, 1) ## 0.U(1.W), alu.io.out)
  val badTarget = jumps && target(1, 0) =/= 0.U
  val causeEx   = Mux(e.cause === HaltCause.Running && badTarget, HaltCause.Misaligned, e.cause)
  val redirect  = idEx.valid && jumps && causeEx === HaltCause.Running && !memWait
  val executed  = Wire(Valid(new ExMem))
  executed.valid          := idEx.valid
  executed.bits.pc        := e.pc
  executed.bits.inst      := e.inst
  executed.bits.ctrl      := e.ctrl
  executed.bits.result    := alu.io.out
  executed.bits.storeData := rs2Data
  executed.bits.nextPc    := Mux(jumps, target, e.pc + 4.U)
  executed.bits.cause     := causeEx
  val exMem = Stage(executed, flush.io.flushExMem, enable = !memWait)

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
  val aluOrLink  = Mux(link, linkPc, m.result)                   // what MEM can forward: not a loaded value
  val writeValue = Mux(load, extend.io.value, aluOrLink)
  memWait := access && !io.dmem.resp.valid
  val retiring = Wire(Valid(new RetireInfo))
  val r        = retiring.bits
  retiring.valid   := exMem.valid && !memWait
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
  val haltInMem = exMem.valid && halts && !memWait
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

  // ---- forwarding: the results of the instructions in MEM (not a load: its value is not there
  // before MEM) and in WB
  fromExMem.valid      := exMem.valid && m.cause === HaltCause.Running && (is(m.ctrl, Kind.Alu) || link)
  fromExMem.bits.rd    := rd
  fromExMem.bits.value := aluOrLink
  fromMemWb.valid      := memWb.valid
  fromMemWb.bits.rd    := memWb.bits.rd // 0 if it writes nothing
  fromMemWb.bits.value := memWb.bits.rdValue

  // ---- the next pc, and the flushes
  flush.io.redirect := redirect
  flush.io.halt     := haltInMem
  when(redirect)(pc := target).elsewhen(fetched.valid && ifMoves)(pc := pc + 4.U)
}

/** The pipeline with hazard handling, with its RAM, for tests and notebooks. */
class ForwardingSystem extends CoreSystem(new ForwardingCore)
