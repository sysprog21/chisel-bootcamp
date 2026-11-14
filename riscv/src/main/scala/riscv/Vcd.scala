package riscv

import scala.collection.mutable

/** A waveform read from a VCD (Value Change Dump) file, as `simulate(..., waves = ...)` writes:
  * every signal's value over time. Signals are named by their path, e.g.
  * `TOP.svsimTestbench.dut.core.f.io_value`.
  */
class Vcd(lines: Iterator[String]) {
  def this(text: String) = this(text.linesIterator)

  private val ids     = mutable.LinkedHashMap[String, String]() // signal path -> VCD identifier
  private val changes = mutable.Map[String, mutable.ArrayBuffer[(Long, BigInt)]]()

  locally {
    val scope                            = mutable.ArrayBuffer[String]()
    var time                             = 0L
    def parse(bits: String): BigInt      = if (bits.forall("01".contains(_))) BigInt(bits, 2) else -1 // x or z: -1
    def change(id: String, bits: String) = changes.getOrElseUpdate(id, mutable.ArrayBuffer()) += time -> parse(bits)
    for (tokens <- lines.map(_.trim.split("\\s+").toList).filter(_.head.nonEmpty)) tokens match {
      case "$scope" :: _ :: name :: _                          => scope += name
      case "$upscope" :: _                                     => scope.remove(scope.size - 1)
      case "$var" :: _ :: _ :: id :: name :: _                 => ids((scope :+ name).mkString(".")) = id
      case t :: Nil if t.startsWith("#")                       => time = t.drop(1).toLong
      case v :: id :: Nil if v.startsWith("b")                 => change(id, v.drop(1))
      case v :: Nil if "01xzXZ".contains(v.head) && v.size > 1 => change(v.drop(1), v.take(1))
      case _                                                   => // other declarations, $dumpvars, $end
    }
  }

  /** The paths of all signals, in the order the file declares them. */
  def signals: Seq[String] = ids.keys.toSeq

  /** The one signal whose path is `suffix` or ends with `.suffix` (e.g. `core.f.io_value`). */
  def find(suffix: String): String = signals.filter(s => s == suffix || s.endsWith("." + suffix)) match {
    case Seq(one)             => one
    case none if none.isEmpty => throw new NoSuchElementException(s"no signal ends with $suffix")
    case many                 => throw new IllegalArgumentException(s"$suffix is ambiguous: ${many.mkString(", ")}")
  }

  private def changesOf(signal: String) =
    changes.getOrElse(ids(if (ids.contains(signal)) signal else find(signal)), Nil)

  /** `signal` (a path, or the end of one) as a function of time: its value at a time (-1 for x or
    * z, before its first change, or for a real-valued signal, which this reader does not decode).
    * The signal is looked up once, and each value is found by binary search.
    */
  def apply(signal: String): Long => BigInt = {
    val c = changesOf(signal) // in time order, as the file lists them
    time => {
      // the last change at or before `time`: c(lo - 1) once lo and hi meet
      var lo = 0
      var hi = c.size
      while (lo < hi) {
        val mid = (lo + hi) / 2
        if (c(mid)._1 <= time) lo = mid + 1 else hi = mid
      }
      if (lo == 0) BigInt(-1) else c(lo - 1)._2
    }
  }

  /** The times at which `clock` rises: one per cycle. */
  def risingEdges(clock: String): Seq[Long] =
    changesOf(clock).toSeq.sliding(2).collect { case Seq((_, a), (t, b)) if a == 0 && b == 1 => t }.toSeq
}

object Vcd {

  /** Reads the file line by line: a long simulation's dump need not fit in memory as one string. */
  def read(path: os.Path): Vcd =
    scala.util.Using.resource(scala.io.Source.fromFile(path.toIO))(s => new Vcd(s.getLines()))
}
