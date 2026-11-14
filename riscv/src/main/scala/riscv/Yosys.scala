package riscv

import chisel3.RawModule
import _root_.circt.stage.ChiselStage

/** Running Yosys from Scala: `yosys`, or `yowasp-yosys` (YoWASP's build, which the bootcamp image
  * has), on the PATH.
  */
object Yosys {

  /** The Yosys command on the PATH, if there is one. */
  lazy val command: Option[String] = {
    val path = sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator)
    Seq("yosys", "yowasp-yosys").find(cmd => path.exists(d => new java.io.File(d, cmd).canExecute))
  }

  /** Whether Yosys can be run. */
  def available: Boolean = command.isDefined

  /** The SystemVerilog of `gen`, as Yosys's reader takes it: without layers (assertions), random
    * initial values, packed arrays, or variables declared inside `always` blocks.
    */
  def verilog(gen: => RawModule): String = ChiselStage.emitSystemVerilog(
    gen,
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=disable",
      "--lowering-options=disallowLocalVariables,disallowPackedArrays"
    )
  )

  /** What a tool printed (its output and errors), its exit code, and the files it wrote. */
  case class Result(log: String, exitCode: Int, files: Map[String, String])

  /** Runs `command` in a temporary directory that holds `files` (name -> contents), and returns
    * what it printed and the files named in `outputs` that it wrote. The directory is removed.
    */
  def runIn(command: Seq[String], files: Map[String, String], outputs: Seq[String] = Nil): Result = {
    val dir = os.temp.dir(prefix = "tool", deleteOnExit = false)
    try {
      for ((name, text) <- files) os.write(dir / name, text)
      val out = os
        .proc(command)
        .call(
          cwd = dir,
          check = false,
          mergeErrIntoOut = true,
          timeout = 15 * 60 * 1000
        ) // Yosys's first run compiles it
      Result(
        out.out.text(),
        out.exitCode,
        outputs.filter(o => os.exists(dir / o)).map(o => o -> os.read(dir / o)).toMap
      )
    } finally os.remove.all(dir)
  }

  /** Runs the Yosys `script` on `files` (see `runIn`). */
  def run(script: String, files: Map[String, String], outputs: Seq[String] = Nil): Result =
    runIn(Seq(command.getOrElse(sys.error("Yosys is not installed")), "-p", script), files, outputs)
}
