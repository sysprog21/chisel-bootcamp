package riscv

import chisel3._
import chisel3.simulator.PeekPokeAPI._

import scala.collection.mutable

/** Runs programs on a simulated core with its RAM (a [[RiscvSystem]], such as
  * [[SingleCycleSystem]]) and compares what it retires with the golden model ([[IsaSim]]). Use it
  * inside `simulate(new SingleCycleSystem) { c => ... }`.
  */
object CoSim {

  /** Writes `image` into the RAM from the reset pc (with reset held), then releases reset. The rest
    * of the RAM keeps what it held (unknown at first; zeroing 64 KB would take 16384 cycles), where
    * the golden model has zeros: a program must not read memory that it did not load or store.
    */
  def load(c: RiscvSystem, image: Seq[Long]): Unit = {
    c.reset.poke(true.B)
    c.io.load.valid.poke(true.B)
    for ((word, i) <- image.zipWithIndex) {
      c.io.load.bits.addr.poke((Ram.base + 4L * i).U) // where the core starts, and Asm's base
      c.io.load.bits.data.poke(word.U)
      c.clock.step()
    }
    c.io.load.valid.poke(false.B)
    c.clock.step() // reset the core once more, now that the program is in place
    c.reset.poke(false.B)
  }

  private val causes = Map(
    HaltCause.Ecall.litValue      -> Halt.Ecall,
    HaltCause.Ebreak.litValue     -> Halt.Ebreak,
    HaltCause.Illegal.litValue    -> Halt.Illegal,
    HaltCause.Misaligned.litValue -> Halt.Misaligned,
    HaltCause.BadAddress.litValue -> Halt.BadAddress
  )

  /** The instruction the core retires in this cycle, as a golden-model record, if any. */
  def retired(c: RiscvSystem): Option[Retire] =
    if (!c.io.retire.valid.peek().litToBoolean) None
    else {
      val r   = c.io.retire.bits
      val mem = r.mem.bits
      Some(
        Retire(
          pc = r.pc.peek().litValue.toLong,
          inst = r.inst.peek().litValue.toLong,
          nextPc = r.nextPc.peek().litValue.toLong,
          rd = r.rd.peek().litValue.toInt,
          rdValue = r.rdValue.peek().litValue.toLong,
          mem = Option.when(r.mem.valid.peek().litToBoolean)(
            MemAccess(
              mem.addr.peek().litValue.toLong,
              mem.write.peek().litToBoolean,
              mem.bytes.peek().litValue.toInt,
              mem.data.peek().litValue.toLong
            )
          ),
          halt = causes.get(r.halt.peek().litValue)
        )
      )
    }

  /** Runs the loaded program until the instruction that halts the core retires, for at most
    * `maxCycles` cycles; returns what retired (in order). A core may retire nothing in a cycle (a
    * pipeline filling up, a stall), but at most one instruction.
    */
  def run(c: RiscvSystem, maxCycles: Int = 100000): Seq[Retire] = {
    val trace  = mutable.ArrayBuffer[Retire]()
    var cycles = 0
    var done   = false
    while (!done && cycles < maxCycles) {
      val r = retired(c) // before `halted`: a core may raise it in the cycle its halt retires
      trace ++= r
      done = r.exists(_.halt.isDefined) || c.io.halted.peek().litToBoolean
      if (!done) c.clock.step() // stay on the halt: the registers can be read
      cycles += 1
    }
    trace.toSeq
  }

  /** The value of register `n` (through the debug port). */
  def reg(c: RiscvSystem, n: Int): Long = {
    c.io.dbg.addr.poke(n.U)
    c.io.dbg.data.peek().litValue.toLong
  }

  /** Runs `image` on the core and on the golden model (see `load`: it must not read memory past its
    * image that it has not written); returns the first difference between what
    * they retire, or that the core did not halt in `maxCycles` cycles, or that it retired something
    * in the few cycles it is run past its halt (None if they retire the same instructions and both
    * halt).
    */
  def compare(c: RiscvSystem, image: Seq[Long], maxCycles: Int = 100000): Option[String] = {
    load(c, image)
    val core                    = run(c, maxCycles)
    val model                   = new IsaSim(image).run(maxCycles)
    def show(r: Option[Retire]) = r.fold("(nothing)")(_.toString)
    def differ(i: Int)          =
      Some(s"instruction $i:\n  core:  ${show(core.lift(i))}\n  model: ${show(model.lift(i))}")
    val i = core.zip(model).indexWhere { case (a, b) => a != b }
    if (i >= 0) differ(i)
    else if (!core.lastOption.exists(_.halt.isDefined))
      Some(s"the core did not halt within $maxCycles cycles (it retired ${core.size} instructions)")
    else if (core.size != model.size) differ(core.size min model.size)
    else afterHalt(c)
  }

  /** Nothing may retire after the instruction that halts the core: a pipeline that failed to flush
    * a younger instruction would retire it a few cycles later (after changing memory or registers).
    */
  private def afterHalt(c: RiscvSystem, cycles: Int = 8): Option[String] =
    (1 to cycles).iterator.flatMap { _ => c.clock.step(); retired(c) }.nextOption().map { r =>
      s"an instruction retired after the one that halted the core:\n  $r"
    }
}
