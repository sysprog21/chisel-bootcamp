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
interp.configureCompiler(
  _.settings.processArguments(List("-language:reflectiveCalls", "-Ytasty-reader"), processAll = true)
)

// Uncomment and change to use proxy
// System.setProperty("https.proxyHost", "proxy.example.com")
// System.setProperty("https.proxyPort", "3128")

import $ivy.`org.chipsalliance::chisel:7.16.0`
import $ivy.`org.scalatest::scalatest:3.2.19`

// Chisel 7 emits SystemVerilog through CIRCT's `firtool`. Chisel downloads a matching
// firtool automatically (the `llvm-firtool` artifact) on first use. Simulation with
// ChiselSim (chisel3.simulator) additionally needs Verilator 5.x and a C++ toolchain.

// Almond 0.14.5 cannot report an exception whose stack trace has "..." frames, which Chisel puts
// in the stack traces of errors raised while it elaborates a module (e.g. a `???` exercise inside
// `new MyModule`): the cell then never finishes (see docs/troubleshooting.md). `scrub` drops the
// (uninteresting) stack traces of such exceptions so that the error message is shown.
def scrub[T](body: => T): T =
  try body
  catch {
    // not the fatal ones (as `scala.util.control.NonFatal`, which this file cannot import before
    // `-Ytasty-reader` is set): `???` is a NotImplementedError, an `Error`, so it must be caught
    case e: Throwable
        if !(e.isInstanceOf[VirtualMachineError] || e.isInstanceOf[LinkageError] ||
          e.isInstanceOf[InterruptedException]) =>
      var t: Throwable = e
      while (t != null) { t.setStackTrace(Array.empty); t = t.getCause }
      throw e
  }

// Convenience function to invoke Chisel and grab emitted SystemVerilog. Chisel 7 puts assertions
// in verification layers, extra files that follow the module (after a `// ----- 8< ----- FILE`
// line each, as firtool 1.160 writes them: recheck when FIRTOOL_VERSION changes); those of a
// module without assertions only hold include guards, and are then left out (all of them; one with
// an assertion keeps them all, as they include each other).
def getVerilog(dut: => chisel3.RawModule): String = {
  val sv                       = scrub(circt.stage.ChiselStage.emitSystemVerilog(dut))
  val marker                   = "// ----- 8< ----- FILE"
  val parts                    = sv.split(s"(?m)^(?=${java.util.regex.Pattern.quote(marker)})")
  def guardsOnly(part: String) = part.linesIterator.drop(1).map(_.trim).forall { l =>
    l.isEmpty || l.startsWith("`ifndef") || l.startsWith("`define") || l.startsWith("`endif") ||
    l.startsWith("`include") || l.startsWith("//")
  }
  val (layers, design) = parts.partition(_.startsWith(marker))
  (if (layers.forall(guardsOnly)) design else parts).mkString.stripTrailing + "\n"
}

// Convenience function to invoke Chisel and grab emitted FIRRTL.
def getFirrtl(dut: => chisel3.RawModule): String =
  scrub(circt.stage.ChiselStage.emitCHIRRTL(dut))

// ChiselSim's `EphemeralSimulator.simulate`: elaborates `module`, compiles it with Verilator and
// runs `body` against it. Two differences and two options, so that the notebooks stay short:
//  * errors go through `scrub`;
//  * a `Module` is reset for one clock cycle before `body` runs, like chiseltest's `test`
//    did, and like the `simulate` of ChiselSim's ScalaTest trait. (`simulateRaw`, which this one
//    calls, and EphemeralSimulator's `simulate` leave the reset to you: until it is asserted,
//    registers hold random values.)
//  * the option `randomization` chooses the initial values of registers and memories. ChiselSim's
//    default randomizes both; `Randomization.random.copy(memories = false)` leaves memories alone,
//    which a memory loaded from a file needs (the random values would overwrite its contents).
//  * the option `waves = "name"` dumps a VCD waveform from time zero into build/name/ (kept after
//    the simulation, unlike the temporary directory used otherwise) and prints its path.
// The `poke`/`peek`/`expect` methods come from `import chisel3.simulator.PeekPokeAPI._`.
def simulate[T <: chisel3.RawModule](
    module: => T,
    randomization: chisel3.simulator.Randomization = chisel3.simulator.Randomization.random,
    waves: String = ""
)(body: T => Unit): Unit = {
  import chisel3.fromBooleanToLiteral
  import chisel3.simulator.PeekPokeAPI._
  import chisel3.simulator.{ChiselSim, LayerControl, Settings}
  import svsim.verilator.Backend.CompilationSettings.{TraceKind, TraceStyle}
  require(waves.matches("[A-Za-z0-9_-]*"), s"waves: use letters, digits, '_' and '-' only, not '$waves'")
  val waveDir = Option.when(waves.nonEmpty)(wavesDir(waves))
  // what EphemeralSimulator.simulate does, with the two options added
  implicit val directory: chisel3.testing.HasTestingDirectory = waveDir match {
    case Some(dir) => new chisel3.testing.HasTestingDirectory { def getDirectory = dir.toNIO }
    case None      => chisel3.testing.HasTestingDirectory.temporary(deleteOnExit = true)
  }
  // VCD support, as ChiselSim's -DemitVcd=1 test option sets it for Verilator
  implicit val backendSettings: svsim.BackendSettingsModifications = {
    case s: svsim.verilator.Backend.CompilationSettings if waveDir.nonEmpty =>
      s.withTraceStyle(Some(TraceStyle(TraceKind.Vcd)))
    case s => s
  }
  val settings =
    Settings
      .defaultRaw[T]
      .copy(
        verilogLayers = LayerControl.EnableAll,
        randomization = randomization,
        enableWavesAtTimeZero = waveDir.nonEmpty
      )
  try
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
  finally // also when the test fails, which is when the waves are most useful
    if (waves.nonEmpty) wavesFile(waves).foreach(vcd => println(s"Waveform: $vcd"))
}

// Where `simulate(..., waves = name)` writes its files.
def wavesDir(name: String): os.Path = os.pwd / "build" / name

// The VCD file written by `simulate(..., waves = name)`, if there is one.
def wavesFile(name: String): Option[os.Path] = {
  val dir = wavesDir(name)
  if (os.exists(dir)) os.walk(dir).find(_.ext == "vcd") else None
}

// Start and stop dumping waves inside a `simulate(..., waves = "name")` body, for example to
// skip an uninteresting start-up phase. (Without `waves`, the simulation has no trace support and
// these fail.)
object waveControl extends chisel3.simulator.ControlAPI // Chisel has no ControlAPI object to import
def enableWaves(): Unit  = waveControl.enableWaves()
def disableWaves(): Unit = waveControl.disableWaves()

// Visualization: turn the FIRRTL emitted by Chisel into a Graphviz graph.
// FIRRTL (spec 4.x, as emitted by Chisel 7) is line-oriented text, so a few regular
// expressions are enough to recover ports, registers, wires, nodes and connections.
def firrtlToDot(firrtlString: String): String = {
  val lines = firrtlString.split("\n").map(_.replaceAll("@\\[.*\\]", ""))

  // The references in an expression: a name, then any `.field` and `[index]`, where an index is a
  // number or itself a reference (`io.v[io.idx[0]]`, `w[i][j]`). A name directly followed by `(` is
  // a primitive operation (`add(a, b)`), not a signal.
  def isNameStart(c: Char)                  = c.isLetter || c == '_'
  def isNamePart(c: Char)                   = c.isLetterOrDigit || c == '_'
  def references(expr: String): Seq[String] = {
    val found        = scala.collection.mutable.ArrayBuffer[String]()
    var i            = 0
    def name(): Unit = while (i < expr.length && isNamePart(expr(i))) i += 1
    while (i < expr.length)
      if (isNameStart(expr(i)) && (i == 0 || !isNamePart(expr(i - 1)))) {
        val start = i
        name()
        var more = true
        while (more && i < expr.length)
          if (expr(i) == '.' && i + 1 < expr.length && isNameStart(expr(i + 1))) { i += 1; name() }
          else if (expr(i) == '[') {
            var depth = 1 // up to the matching `]`
            i += 1
            while (depth > 0 && i < expr.length) {
              if (expr(i) == '[') depth += 1 else if (expr(i) == ']') depth -= 1
              i += 1
            }
          } else more = false
        if (i >= expr.length || expr(i) != '(') found += expr.substring(start, i)
      } else i += 1
    found.toSeq
  }
  def isFixed(index: String) = index.forall(_.isDigit) // `v[0]`, not `v[i]`
  // A reference without its indices, as it was declared: `io.v.a` of `io.v[io.idx[0]].a`
  def unindexed(ref: String): String = {
    var depth = 0
    ref.filter { c =>
      if (c == '[') depth += 1
      val outside = depth == 0
      if (c == ']') depth -= 1
      outside
    }
  }
  // The [start, end] of each outermost `[...]` of a reference: `w[i][j]` has two, `v[idx[0]]` one
  def brackets(ref: String): Seq[(Int, Int)] = {
    var depth = 0
    var open  = 0
    ref.indices.flatMap { i =>
      ref(i) match {
        case '[' => if (depth == 0) open = i; depth += 1; None
        case ']' => depth -= 1; if (depth == 0) Some(open -> i) else None
        case _   => None
      }
    }
  }
  val literal = "(?:UInt|SInt)(?:<[0-9]+>)?\\([^)]*\\)".r // e.g. UInt<1>(0h1)
  val primOp  = "^([a-z0-9_]+)\\(.*".r                    // e.g. add(a, b)

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
  val module  = "^(?:public )?(?:module|extmodule) ([A-Za-z0-9_]+) :.*".r
  val top     = lines.iterator
    .map(_.trim)
    .collectFirst { case circuit(n) => n }
    .getOrElse(throw new IllegalArgumentException("FIRRTL circuit name is missing"))
  val controls    = scala.collection.mutable.LinkedHashSet[(String, String)]()
  val instance    = "^inst ([A-Za-z0-9_]+) of ([A-Za-z0-9_]+).*".r
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
  val portToken                                             = "\\{|\\}|(flip )?([A-Za-z0-9_]+) :".r
  def addPort(dir: String, name: String, tpe: String): Unit = {
    def add(path: String, isInput: Boolean): Unit = (if (isInput) inputs else outputs) += path
    if (!tpe.startsWith("{")) add(name, dir == "input")
    else {
      var scopes        = List((name, dir == "input"))
      var pending       = Option.empty[(String, Boolean)] // the last field, until we know if it is a leaf
      def flush(): Unit = { pending.foreach(p => add(p._1, p._2)); pending = None }
      portToken.findAllMatchIn(tpe).foreach { m =>
        m.matched match {
          case "{" => pending.foreach(p => scopes ::= p); pending = None
          case "}" => flush(); scopes = scopes.tail
          case _   =>
            flush()
            val (path, isInput) = scopes.head
            pending = Some((path + "." + m.group(2), isInput ^ (m.group(1) != null)))
        }
      }
    }
  }

  val fixedSelections   = scala.collection.mutable.LinkedHashSet[(String, String)]()
  val reads             = scala.collection.mutable.LinkedHashSet[String]() // what each expression reads
  val writes            = scala.collection.mutable.LinkedHashSet[String]() // and drives
  val dynamicReads      = scala.collection.mutable.LinkedHashSet[String]()
  val dynamicWrites     = scala.collection.mutable.LinkedHashSet[String]()
  val dynamicSelections = scala.collection.mutable.Set[String]()           // `w[i]`, and `w[i][0]` of it
  def visit(moduleName: String, prefix: String, ancestors: Set[String]): Unit = {
    if (ancestors(moduleName)) throw new IllegalArgumentException("Recursive FIRRTL module: " + moduleName)
    val body = moduleLines.getOrElse(
      moduleName,
      throw new IllegalArgumentException("FIRRTL module definition is missing: " + moduleName)
    )
    // A node is named by its FIRRTL reference, scoped by the instances it is in, the index of a
    // dynamic selection too (`a.v[a.i]`); references that differ stay different nodes (#18).
    def scoped(ref: String): String = {
      val name = new StringBuilder(prefix)
      var from = 0
      brackets(ref).foreach { case (open, close) =>
        val index = ref.substring(open + 1, close)
        name ++= ref.substring(from, open) ++= "[" ++= (if (isFixed(index)) index else scoped(index)) ++= "]"
        from = close + 1
      }
      (name ++= ref.substring(from)).toString
    }

    // A selection is drawn from its base (written: to it) and, for a dynamic one, from its index;
    // the base and the index are references of their own (`w[i]` of `w[i][j]`). A fixed selection
    // (`v[0]`) is joined to its base only if the base is also selected dynamically (see below), or is
    // itself a dynamic selection (`w[i][0]`).
    def reference(ref: String, write: Boolean = false): String = {
      val selected = scoped(ref)
      brackets(ref).lastOption.foreach { case (open, close) =>
        val base  = reference(ref.substring(0, open), write)
        val index = ref.substring(open + 1, close)
        if (isFixed(index) && !dynamicSelections(base)) fixedSelections += (base -> selected)
        else {
          if (write) edges += (selected -> base) else edges += (base -> selected)
          if (!isFixed(index)) {
            (if (write) dynamicWrites else dynamicReads) += base
            sources(index).foreach(s => edges += (s -> selected))
          }
          dynamicSelections += selected
        }
      }
      selected
    }
    def sources(expr: String): Seq[String] = {
      val read = references(literal.replaceAllIn(expr, "")).filterNot(Set("clock", "reset")).map(ref => reference(ref))
      reads ++= read
      read
    }

    var conditions         = List.empty[(Int, Seq[String])]
    var previousConditions = Map.empty[Int, Seq[String]]
    // `expr` drives `target`, as does every enclosing `when` condition (the dashed edges)
    def drive(expr: String, target: String): Unit = {
      sources(expr).foreach(s => edges += (s -> target))
      writes += target
      conditions.flatMap(_._2).foreach(s => controls += (s -> target))
    }
    body.foreach { raw =>
      val line   = raw.trim
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
        } else
          line match {
            case instance(n, child)    => visit(child, prefix + n + ".", ancestors + moduleName)
            case declPort(dir, n, tpe) =>
              if (prefix.isEmpty && n != "clock" && n != "reset") addPort(dir, n, tpe)
            case declReg(n)        => regs += scoped(n)
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
  // A whole vector or bundle and its parts (`w` and `w[0]`, `io` and `io.a`) are the same wires: what
  // is written to one reaches what reads the other (`connect w[0], a` then `connect out, w`). An input
  // port's fields are written from outside, and an output port's are read there (`connect o, r`).
  // A dynamic selection is already joined to its base (`w` of `w[i]`), so the wholes stop at it.
  def wholes(part: String): Seq[String] = { // `w` and `w[0]` of `w[0].a`; not inside an index
    val from  = brackets(part).filterNot { case (o, c) => isFixed(part.substring(o + 1, c)) }.lastOption.fold(-1)(_._1)
    var depth = 0
    part.indices.flatMap { i =>
      val cut = depth == 0 && i > from && (part(i) == '.' || part(i) == '[')
      if (part(i) == '[') depth += 1 else if (part(i) == ']') depth -= 1
      if (cut) Some(part.substring(0, i)) else None
    }
  }
  val written = writes ++ inputs
  val read    = reads ++ outputs
  val used    = written ++ read
  for (part <- used; whole <- wholes(part) if used(whole)) {
    if (written(part) && read(whole)) edges += (part  -> whole)
    if (written(whole) && read(part)) edges += (whole -> part)
  }
  // What is written through a dynamic selection may reach any fixed one of the same base, and the
  // other way round; this carries down a nested vector (`w[0][1]` beside `w[i][j]`), as a base is
  // recorded before its selections.
  fixedSelections.foreach { case (base, selected) =>
    if (dynamicWrites(base)) { edges += (base -> selected); dynamicWrites += selected }
    if (dynamicReads(base)) { edges += (selected -> base); dynamicReads += selected }
  }

  // A node is what was declared, or a sub-field or element of it: `zs[1]` of `zs`, `io.v[0].a` of the
  // port field `io.v.a` (the declaration has no indices). A node whose name merely starts with a
  // declared one (`foo_tmp` beside a port `foo`) is not (#19).
  def declaredAs(path: String, set: scala.collection.Set[String]): Boolean =
    set.exists(d => path == d || path.startsWith(d + "."))
  // Every id is quoted, so that any reference, and names such as `node` or `edge`, are valid DOT
  def quote(id: String): String = "\"" + id + "\""

  // Ports and registers are drawn even when only a constant drives them (so they have no edge)
  val nodes = ((edges ++ controls).flatMap { case (a, b) => Seq(a, b) }.toSeq ++ inputs ++ outputs ++ regs).distinct
  val dot   = new StringBuilder
  dot ++= s"digraph circuit {\n  label=\"Module: $top\";\n  rankdir=LR;\n  node [shape=box, style=\"rounded,filled\", fillcolor=lightgray];\n"
  // Fill colors from the Okabe-Ito palette, lightened (sky blue, orange, reddish purple), which
  // color-blind readers tell apart; the shapes alone also tell inputs (circle), outputs (double
  // circle) and registers (3D box) apart
  nodes.foreach { n =>
    val path  = unindexed(n)
    val attrs =
      if (declaredAs(path, inputs)) "shape=circle, fillcolor=\"#9ccfee\""
      else if (declaredAs(path, outputs)) "shape=doublecircle, fillcolor=\"#f3c766\""
      else if (declaredAs(path, regs)) "shape=box3d, fillcolor=\"#e3b3cf\""
      else if (opLabel.contains(n)) {
        val name = if (n.split('.').last.startsWith("_")) "" else "\\n" + n // temporaries show only the operation
        s"""shape=ellipse, fillcolor=white, label="${opLabel(n)}$name""""
      } else "shape=box, fillcolor=lightgray"
    dot ++= s"  ${quote(n)} [$attrs];\n"
  }
  def emit(es: Iterable[(String, String)], style: String): Unit =
    es.foreach { case (a, b) => if (a != b) dot ++= s"  ${quote(a)} -> ${quote(b)}$style;\n" }
  emit(edges, "")
  emit(controls, " [style=dashed, label=\"condition\"]")
  dot ++= "}\n"
  dot.toString
}

// Draw the circuit with Graphviz (`dot`). Falls back to printing the FIRRTL without Graphviz.
def visualize(gen: () => chisel3.RawModule): Unit = {
  val firrtlString = getFirrtl(gen())
  val svg          =
    try
      Some(os.proc("dot", "-Tsvg").call(stdin = firrtlToDot(firrtlString), check = false))
        .filter(_.exitCode == 0)
        .map(_.out.text())
    catch { case _: java.io.IOException => None }
  svg.filter(_.nonEmpty) match {
    case Some(html) => almond.display.Html(html).display()
    case None       =>
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
