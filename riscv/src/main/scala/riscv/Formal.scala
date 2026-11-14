package riscv

import chisel3._

/** A formal check with Yosys (see [[Yosys]]). */
object Formal {

  /** Proves with Yosys's SAT solver that output `ok` of `check`, a combinational module (no
    * registers or memories: Yosys refuses them without a sequential proof), is 1 for every value of
    * its inputs. Returns None if it is proved, or the counterexample Yosys found: the value of each
    * input and output, one per line.
    */
  def prove(check: => RawModule, ok: String = "io_ok"): Option[String] = {
    require(ok.matches("[A-Za-z_][A-Za-z0-9_]*"), s"not a signal name: $ok") // it goes into a Yosys script
    val script = s"read_verilog -sv check.sv; prep -auto-top; flatten; sat -prove $ok 1 -show-inputs -show-outputs"
    val log    = Yosys.run(script, Map("check.sv" -> Yosys.verilog(check))).log
    if (log.contains("SAT proof finished - no model found: SUCCESS!")) None
    else if (log.contains("SAT proof finished - model found: FAIL!")) {
      val table = log.linesIterator.dropWhile(!_.trim.startsWith("Signal Name")).drop(2).takeWhile(_.trim.nonEmpty)
      val ports = Seq("\\clock", "\\reset") // a row starts with the signal's name
      Some(table.map(_.trim).filterNot(l => ports.contains(l.takeWhile(!_.isWhitespace))).mkString("\n"))
    } else throw new IllegalStateException(s"yosys did not finish the proof:\n${log.takeRight(3000)}")
  }
}
