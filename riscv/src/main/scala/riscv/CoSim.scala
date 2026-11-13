package riscv

import chisel3._
import chisel3.simulator.PeekPokeAPI._

import scala.collection.mutable

/** Runs programs on a simulated [[SingleCycleSystem]] and compares what it retires with the golden
  * model ([[IsaSim]]). Use it inside `simulate(new SingleCycleSystem) { c => ... }`.
  */
object CoSim {

  /** Writes `image` into the RAM from the reset pc (with reset held), then releases reset. The rest
    * of the RAM keeps what it held (unknown at first; zeroing 64 KB would take 16384 cycles), where
    * the golden model has zeros: a program must not read memory that it did not load or store.
    */
  def load(c: SingleCycleSystem, image: Seq[Long]): Unit = {
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
  def retired(c: SingleCycleSystem): Option[Retire] =
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

  /** Runs the loaded program until the core halts, at most `maxCycles` cycles; returns what retired. */
  def run(c: SingleCycleSystem, maxCycles: Int = 100000): Seq[Retire] = {
    val trace  = mutable.ArrayBuffer[Retire]()
    var cycles = 0
    while (!c.io.halted.peek().litToBoolean && cycles < maxCycles) {
      trace ++= retired(c)
      c.clock.step()
      cycles += 1
    }
    trace.toSeq
  }

  /** The value of register `n` (through the debug port). */
  def reg(c: SingleCycleSystem, n: Int): Long = {
    c.io.dbg.addr.poke(n.U)
    c.io.dbg.data.peek().litValue.toLong
  }

  /** Runs `image` on the core and on the golden model (see `load`: it must not read memory past its
    * image that it has not written); returns the first difference between what they retire (None if
    * they retire the same instructions).
    */
  def compare(c: SingleCycleSystem, image: Seq[Long], maxCycles: Int = 100000): Option[String] = {
    load(c, image)
    val core  = run(c, maxCycles)
    val model = new IsaSim(image).run(maxCycles)
    core.map(Some(_)).zipAll(model.map(Some(_)), None, None).zipWithIndex.collectFirst {
      case ((a, b), i) if a != b =>
        def show(r: Option[Retire]) = r.fold("(nothing retired)")(_.toString)
        s"instruction $i:\n  core:  ${show(a)}\n  model: ${show(b)}"
    }
  }
}
