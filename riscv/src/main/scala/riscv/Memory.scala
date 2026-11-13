package riscv

import chisel3._
import chisel3.util._

/** A request from a core to memory: a word-aligned address, and for a write the data and the byte
  * strobes (`wstrb(k)`: write byte k of the word). `wstrb` 0 is a read.
  */
class MemReq extends Bundle {
  val addr  = UInt(32.W)
  val wdata = UInt(32.W)
  val wstrb = UInt(4.W)
}

/** The memory's answer: the word read (for a write, nothing in particular), or `error` if the
  * address is not in the memory.
  */
class MemResp extends Bundle {
  val rdata = UInt(32.W)
  val error = Bool()
}

/** The memory interface of the cores: the core sends a request (`req`) and the memory answers
  * (`resp`). The cores hold a request until its answer comes and ignore `ready`. The single-cycle
  * and 5.6 cores need both ports to answer in the same cycle as the request (the RAM of this file
  * does); `ForwardingCore` also waits for a data port that answers later, but its instruction port
  * must answer in the same cycle.
  */
class MemPort extends Bundle {
  val req  = Decoupled(new MemReq)
  val resp = Flipped(Valid(new MemResp))
}

/** A register read by a testbench (the register file's debug port). */
class DebugPort extends Bundle {
  val addr = Input(UInt(5.W))
  val data = Output(UInt(32.W))
}

/** A word written into a memory by a testbench before the core runs. */
class LoadPort extends Bundle {
  val addr = UInt(32.W)
  val data = UInt(32.W)
}

object Ram {

  /** Where the RAM of the course is (the cores start there, and riscv-tests expect it), and its
    * size: the cores, the golden model and the programs all use these.
    */
  val base: Long = 0x80000000L
  val bytes: Int = 64 * 1024
}

/** The RAM: `words` 32-bit words from `base`, with an instruction port and a data port that read
  * combinationally and answer in the same cycle (a `Mem`), byte-strobed writes, and a load port for
  * testbenches. It starts with unknown contents. The load port takes priority: a store in the same
  * cycle as a load-port write is dropped (testbenches load while the core is held in reset).
  */
class Ram(words: Int = Ram.bytes / 4, base: Long = Ram.base) extends Module {
  require(isPow2(words) && base % (4L * words) == 0, "the RAM must be aligned to its size")
  val io = IO(new Bundle {
    val imem = Flipped(new MemPort)
    val dmem = Flipped(new MemPort)
    val load = Input(Valid(new LoadPort))
  })

  val mem                          = Mem(words, Vec(4, UInt(8.W)))
  def inRam(addr: UInt): Bool      = addr >= base.U && addr < (base + 4L * words).U
  def index(addr: UInt): UInt      = addr(log2Ceil(words) + 1, 2)
  def bytes(word: UInt): Vec[UInt] = word.asTypeOf(Vec(4, UInt(8.W))) // the same bits, as bytes

  for (port <- Seq(io.imem, io.dmem)) {
    port.req.ready       := true.B
    port.resp.valid      := port.req.valid
    port.resp.bits.rdata := mem(index(port.req.bits.addr)).asUInt
    port.resp.bits.error := !inRam(port.req.bits.addr)
  }
  // One write port, shared by the load port (all four bytes) and the core's stores (the strobed
  // bytes): firtool drops the masks of a memory whose other write port has no mask (a constant
  // all-ones mask counts as none), and a masked write then writes every byte (see 2.8).
  val store = io.dmem.req.valid && io.dmem.req.bits.wstrb.orR && inRam(io.dmem.req.bits.addr)
  val wreq  = Mux(io.load.valid, io.load.bits.addr, io.dmem.req.bits.addr)
  val wdata = Mux(io.load.valid, io.load.bits.data, io.dmem.req.bits.wdata)
  val wstrb = Mux(io.load.valid, "b1111".U, io.dmem.req.bits.wstrb)
  when(io.load.valid || store) {
    mem.write(index(wreq), bytes(wdata), wstrb.asBools)
  }
}
