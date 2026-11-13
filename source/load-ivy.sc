// Chisel 7 requires Scala 2.13 (the Almond kernel is 2.13.18) and the compiler plugin.
// The plugin must be loaded first, with its full Scala version (":::").
import $plugin.$ivy.`org.chipsalliance:::chisel-plugin:7.16.0`

// * Anonymous `new Bundle { ... }` port lists use structural types, which Scala 2.13
//   only accepts with the reflectiveCalls language feature.
// * Chisel 7's dependencies put Scala 3 (TASTy) classes on the classpath; without
//   -Ytasty-reader, e.g. `import scala.util.Random` fails with "Missing dependency ... TASTy".
// * Deprecations: the kernel prints a warning that names each deprecated call in a cell, and
//   runtest.py fails on it. Code in this file only gets a summary line ("1 deprecation ...;
//   re-run enabling -deprecation for details"), and adding -deprecation here changes nothing.
interp.configureCompiler(_.settings.processArguments(List("-language:reflectiveCalls", "-Ytasty-reader"), processAll = true))

// Uncomment and change to use proxy
// System.setProperty("https.proxyHost", "proxy.example.com")
// System.setProperty("https.proxyPort", "3128")

import $ivy.`org.chipsalliance::chisel:7.16.0`
import $ivy.`org.scalatest::scalatest:3.2.19`

// Chisel 7 emits SystemVerilog through CIRCT's `firtool`. Chisel downloads a matching
// firtool automatically (the `llvm-firtool` artifact) on first use. Simulation with
// ChiselSim (chisel3.simulator) additionally needs Verilator 5.x and a C++ toolchain.

// Almond 0.14.5 cannot report an exception whose stack trace Ammonite has trimmed to "..."
// frames: the cell then never finishes. That happens to errors raised while Chisel
// elaborates a module (e.g. a `???` exercise inside `new MyModule`). `scrub` drops the
// (uninteresting) stack traces of such exceptions so that the error message is shown.
def scrub[T](body: => T): T =
  try body
  catch {
    // not the fatal ones (as `scala.util.control.NonFatal`, which this file cannot import before
    // `-Ytasty-reader` is set): `???` is a NotImplementedError, an `Error`, so it must be caught
    case e: Throwable if !(e.isInstanceOf[VirtualMachineError] || e.isInstanceOf[LinkageError] ||
                           e.isInstanceOf[InterruptedException]) =>
      var t: Throwable = e
      while (t != null) { t.setStackTrace(Array.empty); t = t.getCause }
      throw e
  }

// Convenience function to invoke Chisel and grab emitted SystemVerilog.
def getVerilog(dut: => chisel3.RawModule): String =
  scrub(circt.stage.ChiselStage.emitSystemVerilog(dut))

// Convenience function to invoke Chisel and grab emitted FIRRTL.
def getFirrtl(dut: => chisel3.RawModule): String =
  scrub(circt.stage.ChiselStage.emitCHIRRTL(dut))

// ChiselSim's `EphemeralSimulator.simulate`: elaborates `module`, compiles it with Verilator and
// runs `body` against it. Two differences and one option, so that the notebooks stay short:
//  * errors go through `scrub`;
//  * a `Module` is reset for one clock cycle before `body` runs, like chiseltest's `test`
//    did. (ChiselSim itself leaves the reset to you: until it is asserted, registers hold
//    random values.)
//  * the option `randomization` chooses the initial values of registers and memories. ChiselSim's default
//    randomizes both; `Randomization.random.copy(memories = false)` leaves memories alone, which a
//    memory loaded from a file needs (the random values would overwrite the file's contents).
// The `poke`/`peek`/`expect` methods come from `import chisel3.simulator.PeekPokeAPI._`.
def simulate[T <: chisel3.RawModule](
  module:        => T,
  randomization: chisel3.simulator.Randomization = chisel3.simulator.Randomization.random
)(body: T => Unit): Unit = {
  import chisel3.fromBooleanToLiteral
  import chisel3.simulator.PeekPokeAPI._
  import chisel3.simulator.{ChiselSim, LayerControl, Settings}
  // what EphemeralSimulator.simulate does, with the randomization setting added
  implicit val directory: chisel3.testing.HasTestingDirectory =
    chisel3.testing.HasTestingDirectory.temporary(deleteOnExit = true)
  val settings =
    Settings.defaultRaw[T].copy(verilogLayers = LayerControl.EnableAll, randomization = randomization)
  scrub(new ChiselSim {}.simulateRaw(module, settings = settings) { dut =>
    dut match {
      case m: chisel3.Module =>
        m.reset.poke(true.B)
        m.clock.step(1)
        m.reset.poke(false.B)
      case _ =>
    }
    body(dut)
  })
}

// Visualization: turn the FIRRTL emitted by Chisel into a Graphviz graph.
// FIRRTL (spec 4.x, as emitted by Chisel 7) is line-oriented text, so a few regular
// expressions are enough to recover ports, registers, wires, nodes and connections.
def firrtlToDot(firrtlString: String): String = {
  val lines = firrtlString.split("\n").map(_.replaceAll("@\\[.*\\]", ""))

  def sanitize(name: String): String = name.replaceAll("\\[([A-Za-z0-9_.]+)\\]", "_$1").replaceAll("[^a-zA-Z0-9_]", "_")
  // A (possibly sub-field / sub-index) reference, e.g. `io.in` or `zs[1]`.
  // Names directly followed by `(` are primitive operations (`add(a, b)`), not signals.
  val ident = "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[[A-Za-z_][A-Za-z0-9_.]*\\]|\\[[0-9]+\\])*(?![A-Za-z0-9_(])".r
  val literal = "(?:UInt|SInt)(?:<[0-9]+>)?\\([^)]*\\)".r // e.g. UInt<1>(0h1)
  val primOp = "^([a-z0-9_]+)\\(.*".r // e.g. add(a, b)

  val declPort = "^(input|output) ([A-Za-z0-9_]+) : (.*)".r
  val declReg  = "^(?:reg|regreset) ([A-Za-z0-9_]+) :.*".r
  val declNode = "^node ([A-Za-z0-9_]+) = (.*)".r
  val connect  = "^connect ([^,]+), (.*)".r

  val inputs  = scala.collection.mutable.LinkedHashSet[String]()
  val outputs = scala.collection.mutable.LinkedHashSet[String]()
  val regs    = scala.collection.mutable.LinkedHashSet[String]()
  val edges   = scala.collection.mutable.LinkedHashSet[(String, String)]()
  val opLabel = scala.collection.mutable.LinkedHashMap[String, String]()
  val circuit = "^circuit ([A-Za-z0-9_]+) :.*".r
  val module = "^(?:public )?(?:module|extmodule) ([A-Za-z0-9_]+) :.*".r
  val top = lines.iterator.map(_.trim).collectFirst { case circuit(n) => n }
    .getOrElse(throw new IllegalArgumentException("FIRRTL circuit name is missing"))
  val controls = scala.collection.mutable.LinkedHashSet[(String, String)]()
  val instance = "^inst ([A-Za-z0-9_]+) of ([A-Za-z0-9_]+).*".r
  val moduleLines = scala.collection.mutable.LinkedHashMap[String, scala.collection.mutable.ArrayBuffer[String]]()
  var currentModule: Option[String] = None
  lines.foreach { raw =>
    raw.trim match {
      case module(name) =>
        currentModule = Some(name)
        moduleLines.getOrElseUpdate(name, scala.collection.mutable.ArrayBuffer[String]())
      case _ => currentModule.foreach(n => moduleLines(n) += raw)
    }
  }

  // Bundle-typed ports look like `output io : { flip in : UInt<8>, bus : { out : UInt<8> } }`.
  // A field is an input if an even number of `flip`s (its own and its parents') applies to it.
  val portToken = "\\{|\\}|(flip )?([A-Za-z0-9_]+) :".r
  def addPort(dir: String, name: String, tpe: String): Unit = {
    def add(path: String, isInput: Boolean): Unit = (if (isInput) inputs else outputs) += sanitize(path)
    if (!tpe.startsWith("{")) add(name, dir == "input")
    else {
      var scopes = List((name, dir == "input"))
      var pending = Option.empty[(String, Boolean)] // the last field, until we know if it is a leaf
      def flush(): Unit = { pending.foreach(p => add(p._1, p._2)); pending = None }
      portToken.findAllMatchIn(tpe).foreach { m =>
        m.matched match {
          case "{" => pending.foreach(p => scopes ::= p); pending = None
          case "}" => flush(); scopes = scopes.tail
          case _ =>
            flush()
            val (path, isInput) = scopes.head
            pending = Some((path + "_" + m.group(2), isInput ^ (m.group(1) != null)))
        }
      }
    }
  }

  val dynamic = "\\[([A-Za-z_][A-Za-z0-9_.]*)\\]".r
  val index = "\\[([A-Za-z_][A-Za-z0-9_.]*|[0-9]+)\\]".r
  val fixedSelections = scala.collection.mutable.LinkedHashSet[(String, String)]()
  val dynamicReads = scala.collection.mutable.LinkedHashSet[String]()
  val dynamicWrites = scala.collection.mutable.LinkedHashSet[String]()
  def visit(moduleName: String, prefix: String, ancestors: Set[String]): Unit = {
    if (ancestors(moduleName)) throw new IllegalArgumentException("Recursive FIRRTL module: " + moduleName)
    val body = moduleLines.getOrElse(moduleName,
      throw new IllegalArgumentException("FIRRTL module definition is missing: " + moduleName))
    def scoped(name: String): String = sanitize(prefix + name)
    def reference(ref: String, write: Boolean = false): String = {
      val selected = scoped(ref)
      index.findAllMatchIn(ref).foreach { m =>
        val base = scoped(ref.substring(0, m.start))
        if (m.group(1).forall(_.isDigit)) fixedSelections += (base -> selected)
        else if (write) dynamicWrites += base
        else dynamicReads += base
      }
      dynamic.findAllMatchIn(ref).foreach { m =>
        val base = ref.substring(0, m.start)
        if (write) edges += (selected -> scoped(base))
        else edges += (scoped(base) -> selected)
        edges += (scoped(m.group(1)) -> selected)
      }
      selected
    }
    def sources(expr: String): Seq[String] =
      ident.findAllIn(literal.replaceAllIn(expr, "")).filterNot(Set("clock", "reset")).map(ref => reference(ref)).toSeq

    var conditions = List.empty[(Int, Seq[String])]
    var previousConditions = Map.empty[Int, Seq[String]]
    // `expr` drives `target`, as does every enclosing `when` condition (the dashed edges)
    def drive(expr: String, target: String): Unit = {
      sources(expr).foreach(s => edges += (s -> target))
      conditions.flatMap(_._2).foreach(s => controls += (s -> target))
    }
    body.foreach { raw =>
      val line = raw.trim
      val indent = raw.takeWhile(_ == ' ').length
      if (line.nonEmpty) {
        while (conditions.nonEmpty && conditions.head._1 >= indent) {
          val (level, condition) = conditions.head
          previousConditions += level -> condition
          conditions = conditions.tail
        }
        if (line.startsWith("when ") && line.endsWith(" :")) {
          val condition = sources(line.stripPrefix("when ").stripSuffix(" :"))
          conditions = (indent -> condition) :: conditions
          previousConditions -= indent
        } else if (line == "else :") {
          conditions = (indent -> previousConditions.getOrElse(indent, Seq.empty)) :: conditions
        } else line match {
          case instance(n, child) => visit(child, prefix + n + ".", ancestors + moduleName)
          case declPort(dir, n, tpe) =>
            if (prefix.isEmpty && n != "clock" && n != "reset") addPort(dir, n, tpe)
          case declReg(n) => regs += scoped(n)
          case declNode(n, expr) =>
            expr match { case primOp(op) => opLabel(scoped(n)) = op; case _ => }
            drive(expr, scoped(n))
          case connect(dst, src) =>
            val target = reference(dst.trim, write = true)
            drive(src, target)
          case _ =>
        }
      }
    }
  }
  visit(top, "", Set.empty)
  fixedSelections.foreach { case (base, selected) =>
    if (dynamicWrites(base)) edges += (base -> selected)
    if (dynamicReads(base)) edges += (selected -> base)
  }

  // Sub-fields and indices (`zs[1]`) are sanitized into `zs_1`, so they are matched with
  // their declaration by prefix.
  def declaredAs(node: String, set: scala.collection.Set[String]): Boolean =
    set.exists(d => node == d || node.startsWith(d + "_"))

  // Ports and registers are drawn even when only a constant drives them (so they have no edge)
  val nodes = ((edges ++ controls).flatMap { case (a, b) => Seq(a, b) }.toSeq ++ inputs ++ outputs ++ regs).distinct
  val dot = new StringBuilder
  dot ++= s"digraph circuit {\n  label=\"Module: $top\";\n  rankdir=LR;\n  node [shape=box, style=\"rounded,filled\", fillcolor=lightgray];\n"
  nodes.foreach { n =>
    val attrs =
      if (declaredAs(n, inputs)) "shape=circle, fillcolor=lightgreen"
      else if (declaredAs(n, outputs)) "shape=doublecircle, fillcolor=lightcoral"
      else if (declaredAs(n, regs)) "shape=box, fillcolor=gold"
      else if (opLabel.contains(n)) s"""shape=ellipse, fillcolor=white, label="${opLabel(n)}${if (n.startsWith("_")) "" else "\\n" + n}""""
      else "shape=box, fillcolor=lightgray"
    dot ++= s"  $n [$attrs];\n"
  }
  def emit(es: Iterable[(String, String)], style: String): Unit =
    es.foreach { case (a, b) => if (a != b) dot ++= s"  $a -> $b$style;\n" }
  emit(edges, "")
  emit(controls, " [style=dashed, label=\"condition\"]")
  dot ++= "}\n"
  dot.toString
}

// Draw the circuit with Graphviz (`dot`). Falls back to printing the FIRRTL without Graphviz.
def visualize(gen: () => chisel3.RawModule): Unit = {
  val firrtlString = getFirrtl(gen())
  val svg =
    try Some(os.proc("dot", "-Tsvg").call(stdin = firrtlToDot(firrtlString), check = false)).filter(_.exitCode == 0).map(_.out.text())
    catch { case _: java.io.IOException => None }
  svg.filter(_.nonEmpty) match {
    case Some(html) => almond.display.Html(html).display()
    case None =>
      println("Graphviz (`dot`) is not available; showing the FIRRTL instead.")
      println(firrtlString)
  }
}

def visualizeHierarchy(gen: () => chisel3.RawModule): Unit = {
  val firrtlString = getFirrtl(gen())
  println("=== Module Hierarchy ===")
  val hierarchy = firrtlString.split("\n").filter { l =>
    val t = l.trim
    t.startsWith("module ") || t.startsWith("public module ") || t.startsWith("inst ")
  }
  if (hierarchy.nonEmpty) hierarchy.foreach(println) else println("(No submodule instances found)")
  println("\n=== Full FIRRTL ===")
  println(firrtlString)
}
