interp.repositories() ::: List(
  coursierapi.MavenRepository.of("https://oss.sonatype.org/content/repositories/snapshots")
)

@

// Chisel 3.6+ requires the compiler plugin
import $plugin.$ivy.`edu.berkeley.cs:::chisel3-plugin:3.6.+`

interp.configureCompiler(x => x.settings.source.value = scala.tools.nsc.settings.ScalaVersion("2.12.10"))

// Uncomment and change to use proxy
// System.setProperty("https.proxyHost", "proxy.example.com")
// System.setProperty("https.proxyPort", "3128")

import $ivy.`edu.berkeley.cs::chisel3:3.6.+`
import $ivy.`edu.berkeley.cs::chisel-iotesters:2.5.+`
import $ivy.`edu.berkeley.cs::chiseltest:0.6.+`
import $ivy.`edu.berkeley.cs::dsptools:1.5.+`
import $ivy.`org.scalanlp::breeze:1.0`
import $ivy.`edu.berkeley.cs::rocket-dsptools:1.2.0`

// firrtl-diagrammer removed due to json4s compatibility issues with Chisel 3.6
// Visualization functions below provide alternative output

import $ivy.`org.scalatest::scalatest:3.2.2`

// Convenience function to invoke Chisel and grab emitted Verilog.
// Note: emitVerilog has json4s compatibility issues, using FIRRTL as fallback
def getVerilog(dut: => chisel3.Module): String = {
  import chisel3.stage.ChiselStage
  try {
    (new ChiselStage).emitVerilog(dut)
  } catch {
    case e: NoSuchMethodError if e.getMessage.contains("json4s") =>
      println("Warning: Verilog generation has json4s compatibility issues, using FIRRTL")
      (new ChiselStage).emitChirrtl(dut)
    case e: Exception =>
      throw e
  }
}

// Convenience function to invoke Chisel and grab emitted FIRRTL.
def getFirrtl(dut: => chisel3.Module): String = {
  import chisel3.stage.ChiselStage
  (new ChiselStage).emitChirrtl(dut)
}

def compileFIRRTL(
    inputFirrtl: String,
    compiler: firrtl.Compiler,
    customTransforms: Seq[firrtl.Transform] = Seq.empty,
    infoMode: firrtl.Parser.InfoMode = firrtl.Parser.IgnoreInfo,
    annotations: firrtl.AnnotationSeq = firrtl.AnnotationSeq(Seq.empty)
): String = {
  import firrtl.{Compiler, AnnotationSeq, CircuitState, ChirrtlForm, FIRRTLException}
  import firrtl.Parser._
  import scala.io.Source
  import scala.util.control.ControlThrowable
  import firrtl.passes._
  val outputBuffer = new java.io.CharArrayWriter
  try {
      //val parsedInput = firrtl.Parser.parse(Source.fromFile(input).getLines(), infoMode)
      val parsedInput = firrtl.Parser.parse(inputFirrtl.split("\n").toIterator, infoMode)
      compiler.compile(
         CircuitState(parsedInput, ChirrtlForm, annotations),
         outputBuffer,
         customTransforms)
  }

  catch {
    // Rethrow the exceptions which are expected or due to the runtime environment (out of memory, stack overflow)
    case p: ControlThrowable => throw p
    case p: PassException  => throw p
    case p: FIRRTLException => throw p
     // Treat remaining exceptions as internal errors.
       case e: Exception => firrtl.Utils.throwInternalError(exception = Some(e))
  }

  val outputString = outputBuffer.toString
  outputString
}

def stringifyAST(firrtlAST: firrtl.ir.Circuit): String = {
  var ntabs = 0
  val buf = new StringBuilder
  val string = firrtlAST.toString
  string.zipWithIndex.foreach { case (c, idx) =>
    c match {
      case ' ' =>
      case '(' =>
        ntabs += 1
        buf ++= "(\n" + "| " * ntabs
      case ')' =>
        ntabs -= 1
        buf ++= "\n" + "| " * ntabs + ")"
      case ','=> buf ++= ",\n" + "| " * ntabs
      case  c if idx > 0 && string(idx-1)==')' =>
        buf ++= "\n" + "| " * ntabs + c
      case c => buf += c
    }
  }
  buf.toString
}

// Visualization functions - graphical SVG generation using graphviz
def visualize(gen: () => chisel3.RawModule): Unit = {
    import chisel3._
    import chisel3.stage.ChiselGeneratorAnnotation
    import chisel3.stage.phases.{Elaborate, Convert}
    import firrtl.stage.FirrtlCircuitAnnotation
    import sys.process._
    import java.io.{File, PrintWriter}
    import almond.interpreter.api.DisplayData
    import almond.api.helpers.Display

    // Step 1: Elaborate and convert to FIRRTL
    val elaboratePhase = new Elaborate
    val elaborated = elaboratePhase.transform(Seq(ChiselGeneratorAnnotation(gen)))

    val convertPhase = new Convert
    val converted = convertPhase.transform(elaborated)

    val firrtlCircuit = converted.collectFirst {
      case FirrtlCircuitAnnotation(cir) => cir
    }.get

    val firrtlString = firrtlCircuit.serialize

    // Helper function to sanitize node names for DOT format
    def sanitizeName(name: String): String = {
      // Replace array indices with underscores and remove other invalid chars
      name.replaceAll("\\[([0-9]+)\\]", "_$1")
          .replaceAll("[^a-zA-Z0-9_]", "_")
    }

    // Parse FIRRTL to extract structure
    val lines = firrtlString.split("\n")
    val moduleName = lines.find(_.trim.startsWith("module ")).map(_.trim.split(" ")(1).replace(":", "")).getOrElse("Module")
    val inputs = lines.filter(_.trim.startsWith("input ")).map(l => sanitizeName(l.trim.split(" ")(1).split(":")(0)))
    val outputs = lines.filter(_.trim.startsWith("output ")).map(l => sanitizeName(l.trim.split(" ")(1).split(":")(0)))
    val regs = lines.filter(_.trim.startsWith("reg ")).map(l => sanitizeName(l.trim.split(" ")(1).split(":")(0)))
    val wires = lines.filter(_.trim.startsWith("wire ")).map(l => sanitizeName(l.trim.split(" ")(1).split(":")(0)))

    // Generate GraphViz DOT
    val dot = new StringBuilder
    dot ++= "digraph circuit {\n"
    dot ++= "  rankdir=LR;\n"
    dot ++= "  node [shape=box, style=rounded];\n\n"

    // Input nodes
    dot ++= "  subgraph cluster_inputs {\n"
    dot ++= "    label=\"Inputs\";\n"
    dot ++= "    style=filled; color=lightblue;\n"
    inputs.foreach(i => dot ++= s"    $i [shape=circle, fillcolor=lightgreen, style=filled];\n")
    dot ++= "  }\n\n"

    // Output nodes
    dot ++= "  subgraph cluster_outputs {\n"
    dot ++= "    label=\"Outputs\";\n"
    dot ++= "    style=filled; color=lightblue;\n"
    outputs.foreach(o => dot ++= s"    $o [shape=doublecircle, fillcolor=lightcoral, style=filled];\n")
    dot ++= "  }\n\n"

    // Register nodes
    if (regs.nonEmpty) {
      dot ++= "  subgraph cluster_regs {\n"
      dot ++= "    label=\"Registers\";\n"
      dot ++= "    style=filled; color=lightyellow;\n"
      regs.foreach { r =>
        dot ++= s"    $r [shape=box, fillcolor=yellow, style=filled];\n"
      }
      dot ++= "  }\n\n"
    }

    // Parse connections from FIRRTL
    lines.filter(l => l.contains("<=") && !l.trim.startsWith("reset")).foreach { line =>
      val parts = line.trim.split("<=").map(_.trim)
      if (parts.length == 2) {
        val targetRaw = parts(0).split("\\.")(0).split("\\(")(0)
        val sourceRaw = parts(1).split("\\.")(0).split("\\(")(0).split(" ")(0)
        if (!sourceRaw.startsWith("UInt") && !sourceRaw.contains("\"") && !sourceRaw.startsWith("_")) {
          val target = sanitizeName(targetRaw)
          val source = sanitizeName(sourceRaw)
          dot ++= s"  $source -> $target;\n"
        }
      }
    }

    dot ++= "}\n"

    // Write DOT file and generate SVG
    val dotFile = File.createTempFile("circuit", ".dot")
    val svgFile = File.createTempFile("circuit", ".svg")
    val pw = new PrintWriter(dotFile)
    pw.write(dot.toString)
    pw.close()

    // Generate SVG using graphviz
    val result = s"dot -Tsvg ${dotFile.getAbsolutePath} -o ${svgFile.getAbsolutePath}".!

    if (result == 0 && svgFile.exists()) {
      val svgContent = scala.io.Source.fromFile(svgFile).mkString
      Display.html(svgContent)
    } else {
      println("=== Module FIRRTL (Intermediate Representation) ===")
      println(firrtlString)
      println("\nNote: Graphviz not available. Install with: apt-get install graphviz")
    }

    // Cleanup
    dotFile.delete()
    svgFile.delete()
}

def visualizeHierarchy(gen: () => chisel3.RawModule): Unit = {
    import chisel3._
    import chisel3.stage.ChiselGeneratorAnnotation
    import chisel3.stage.phases.{Elaborate, Convert}
    import chisel3.stage.ChiselCircuitAnnotation
    import firrtl.stage.FirrtlCircuitAnnotation

    println("=== Module Hierarchy ===")

    // Elaborate and convert
    val elaboratePhase = new Elaborate
    val elaborated = elaboratePhase.transform(Seq(ChiselGeneratorAnnotation(gen)))

    val convertPhase = new Convert
    val converted = convertPhase.transform(elaborated)

    val firrtlCircuit = converted.collectFirst {
      case FirrtlCircuitAnnotation(cir) => cir
    }.get

    val firrtlString = firrtlCircuit.serialize

    // Print just the module hierarchy
    val hierarchyLines = firrtlString.split("\n").filter(line =>
      line.trim.startsWith("module ") ||
      line.trim.startsWith("inst ") ||
      line.trim.matches("^\\s+inst .*")
    )

    if (hierarchyLines.nonEmpty) {
      hierarchyLines.foreach(println)
    } else {
      println("(No submodule instances found)")
    }

    println("\n=== Full FIRRTL ===")
    println(firrtlString)
}

