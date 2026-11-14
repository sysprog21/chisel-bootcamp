package riscv

import chisel3.RawModule
import org.json4s.{JInt, JObject}
import org.json4s.native.JsonMethods

/** The cells of a design after synthesis for one FPGA family, by type (e.g. `SB_LUT4` -> 103). */
case class Cells(family: String, byType: Map[String, Int]) {
  private def count(p: String => Boolean) = byType.collect { case (t, n) if p(t) => n }.sum

  /** Look-up tables used as logic: each computes any function of 4 inputs (ECP5's `PFUMX` and
    * `L6MUX21` combine LUTs into wider functions, and are not counted).
    */
  def luts: Int = count(t => t == "SB_LUT4" || t == "LUT4")

  /** Flip-flops: the bits of the registers. */
  def flipFlops: Int = count(t => t.startsWith("SB_DFF") || t == "TRELLIS_FF")

  /** Block RAMs: 4 Kbit (iCE40 `SB_RAM40_4K`) or 18 Kbit (ECP5 `DP16KD`), read a cycle after the
    * address.
    */
  def blockRams: Int = count(t => t.startsWith("SB_RAM") || t == "DP16KD")

  /** LUT RAMs (ECP5): pairs of LUTs used as a 16-word, 4-bit RAM, read combinationally. */
  def lutRams: Int = count(_ == "TRELLIS_DPR16X4")

  /** Carry cells, for fast adders and comparators. */
  def carries: Int = count(t => t == "SB_CARRY" || t == "CCU2C")
}

/** Synthesis with Yosys, and a lint with Verilator, for Module 5.10. */
object Synth {

  /** The FPGA families Yosys synthesizes for here: Lattice iCE40 and ECP5, which the open
    * place-and-route tool nextpnr supports.
    */
  val families: Seq[String] = Seq("ice40", "ecp5")

  /** Synthesizes `gen` for each of `families` and counts its cells: one SystemVerilog, read once by
    * one Yosys run that synthesizes a fresh copy of the design for each family.
    */
  def cells(gen: => RawModule, families: Seq[String]): Map[String, Cells] = {
    require(families.forall(this.families.contains), s"not among ${this.families.mkString(", ")}: $families")
    val steps = families.map { f =>
      s"design -load rtl; synth_$f; delete t:$$scopeinfo; tee -q -o $f.json stat -json"
    }
    val script = ("read_verilog -sv top.sv; hierarchy -auto-top; design -save rtl" +: steps).mkString("; ")
    val result = Yosys.run(script, Map("top.sv" -> Yosys.verilog(gen)), families.map(f => s"$f.json"))
    families.map { f =>
      val json =
        result.files.getOrElse(s"$f.json", sys.error(s"yosys did not synthesize:\n${result.log.takeRight(3000)}"))
      val byType = JsonMethods.parse(json) \ "design" \ "num_cells_by_type" match {
        case JObject(fields) => fields.collect { case (t, JInt(n)) => t -> n.toInt }
        case _               => sys.error(s"no cell counts in Yosys's statistics:\n$json")
      }
      f -> Cells(f, byType.toMap)
    }.toMap
  }

  /** Synthesizes `gen` for one `family` and counts its cells. */
  def cells(gen: => RawModule, family: String): Cells = cells(gen, Seq(family))(family)

  /** The warnings of `verilator --lint-only -Wall` on the SystemVerilog of `gen` (but the one that
    * the file is not named after the module); an error if Verilator fails otherwise.
    */
  def lint(gen: => RawModule): Seq[String] = {
    val command = Seq("verilator", "--lint-only", "-Wall", "-Wno-fatal", "-Wno-DECLFILENAME", "top.sv")
    val result  = Yosys.runIn(command, Map("top.sv" -> Yosys.verilog(gen)))
    if (result.exitCode != 0) sys.error(s"verilator failed:\n${result.log}")
    result.log.linesIterator.filter(_.startsWith("%Warning")).toSeq
  }

  /** The designs of Module 5 that `SynthReport` synthesizes. */
  val all: Seq[(String, () => RawModule)] = Seq(
    "Decoder (table)"      -> (() => new Decoder),
    "SwitchDecoder (hand)" -> (() => new SwitchDecoder),
    "SingleCycleCore"      -> (() => new SingleCycleCore),
    "PipelinedCore"        -> (() => new PipelinedCore),
    "ForwardingCore"       -> (() => new ForwardingCore)
  )

  /** A table of the cells of `designs` for each family. */
  def report(designs: Seq[(String, () => RawModule)] = all): String = {
    val row  = "%-22s %-6s %6s %6s %6s %6s %5s"
    val rows =
      for ((name, gen) <- designs; (family, c) <- { val m = cells(gen(), families); families.map(f => f -> m(f)) })
        yield row.format(name, family, c.luts, c.flipFlops, c.carries, c.lutRams, c.blockRams)
    (row.format("design", "family", "LUTs", "FFs", "carry", "LUTRAM", "BRAM") +: rows).mkString("\n")
  }
}

/** Prints the synthesis report of Module 5: `cd riscv && sbt "runMain riscv.SynthReport"`. */
object SynthReport extends App {
  println(Synth.report())
}
