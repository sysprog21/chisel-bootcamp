package riscv

import chisel3._
import _root_.circt.stage.ChiselStage

/** A formal check with Yosys: `yosys`, or `yowasp-yosys` (YoWASP's build, which the bootcamp image
  * has), on the PATH.
  */
object Formal {

  /** The Yosys command on the PATH, if there is one. */
  lazy val yosys: Option[String] = {
    val path = sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator)
    Seq("yosys", "yowasp-yosys").find(cmd => path.exists(d => new java.io.File(d, cmd).canExecute))
  }

  /** Whether Yosys can be run. */
  def available: Boolean = yosys.isDefined

  /** Proves with Yosys's SAT solver that output `ok` of `check`, a combinational module (no
    * registers or memories: Yosys refuses them without a sequential proof), is 1 for every value of
    * its inputs. Returns None if it is proved, or the counterexample Yosys found: the value of each
    * input and output, one per line.
    */
  def prove(check: => RawModule, ok: String = "io_ok"): Option[String] = {
    require(ok.matches("[A-Za-z_][A-Za-z0-9_]*"), s"not a signal name: $ok") // it goes into a Yosys script
    val options = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=disable")
    val sv      = ChiselStage.emitSystemVerilog(check, firtoolOpts = options)
    val dir     = os.temp.dir(prefix = "formal", deleteOnExit = false)
    val script  = s"read_verilog -sv check.sv; prep -auto-top; flatten; sat -prove $ok 1 -show-inputs -show-outputs"
    val log     =
      try {
        os.write(dir / "check.sv", sv)
        os.proc(yosys.getOrElse(sys.error("Yosys is not installed")), "-p", script)
          .call(
            cwd = dir,
            check = false,
            mergeErrIntoOut = true,
            timeout = 15 * 60 * 1000
          ) // the first run compiles Yosys
          .out
          .text()
      } finally os.remove.all(dir)
    if (log.contains("SAT proof finished - no model found: SUCCESS!")) None
    else if (log.contains("SAT proof finished - model found: FAIL!")) {
      val table = log.linesIterator.dropWhile(!_.trim.startsWith("Signal Name")).drop(2).takeWhile(_.trim.nonEmpty)
      val ports = Seq("\\clock", "\\reset") // a row starts with the signal's name
      Some(table.map(_.trim).filterNot(l => ports.contains(l.takeWhile(!_.isWhitespace))).mkString("\n"))
    } else throw new IllegalStateException(s"yosys did not finish the proof:\n${log.takeRight(3000)}")
  }
}
