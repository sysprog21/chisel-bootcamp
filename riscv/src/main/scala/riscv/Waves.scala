package riscv

/** Draws per-cycle samples of signals (read from a `Vcd`, or logged in a test) as an SVG timing
  * diagram, the picture a waveform viewer shows, small enough to sit in a notebook:
  * `almond.display.Html(Waves.svg(...)).display()`.
  *
  * The caller picks the cycles and says when to sample each one (just before its rising clock
  * edge, what the registers take at that edge); every lane then shows one value per cycle. A
  * clock lane comes first. A `Bit` lane is drawn high or low, a `Bus` lane as a band with its
  * value, split where the value changes; `mark` highlights cycles to look at.
  */
object Waves {
  sealed trait Lane { def label: String }

  /** A one-bit signal: high for 1, low for 0, a grey band at mid-level for x or z (-1, or any other
    * value).
    */
  final case class Bit(label: String, value: Long => BigInt) extends Lane

  /** A value per cycle, as text; an empty string draws no value (nothing valid there). */
  final case class Bus(label: String, value: Long => String) extends Lane

  // the widths of a character, for sizing the picture: of the 12 px monospace font of the values, of
  // the 13 px font of the lane labels, and of the 12 px font of the caption
  private val CharWidth   = 7.4
  private val LabelChar   = 7.6
  private val CaptionChar = 7.5 // generous: the font is proportional
  private val RowHeight   = 34
  private val Header      = 30
  private val Mono        = "'JetBrains Mono', 'DejaVu Sans Mono', monospace"

  private def esc(s: String) = s.flatMap {
    case '&' => "&amp;"
    case '<' => "&lt;"
    case '>' => "&gt;"
    case '"' => "&quot;"
    case c   => if (c < ' ' && !"\t\n\r".contains(c)) "\uFFFD" else c.toString // XML allows no other control
  }
  // with a decimal point whatever the JVM's locale: SVG reads a comma as a separator
  private def f(x: Double) = "%.1f".formatLocal(java.util.Locale.ROOT, x)

  /** Runs of equal text: (first cycle, number of cycles, text). */
  private def runs(texts: Seq[String]): List[(Int, Int, String)] =
    texts.indices
      .foldLeft(List.empty[(Int, Int, String)]) {
        case ((s, k, t) :: rest, i) if texts(i) == t => (s, k + 1, t) :: rest
        case (acc, i)                                => (i, 1, texts(i)) :: acc
      }
      .reverse

  /** The diagram. `times`: when to sample each cycle; `names`: what to call each cycle (its
    * number, say); `mark`: indexes into `times` of the cycles to highlight; `note`: a caption.
    */
  def svg(
      times: Seq[Long],
      names: Seq[String],
      lanes: Seq[Lane],
      mark: Set[Int] = Set.empty,
      note: String = ""
  ): String = {
    require(times.nonEmpty && times.size == names.size, "one name per sampled cycle")
    val n        = times.size
    val (ts, ns) = (times.toIndexedSeq, names.toIndexedSeq) // indexed by cycle below, which a List does slowly
    val samples  = lanes.map {                              // a bit per cycle, or the runs of a bus
      case Bit(_, value) => Left(ts.map(value))
      case Bus(_, value) => Right(runs(ts.map(value)))
    }
    val needed =
      samples.collect { case Right(bands) => bands }.flatten.map { case (_, k, t) => (t.length * CharWidth + 16) / k }
    val nameFit                  = ns.map(_.length * CharWidth + 8)
    val cw                       = (needed :+ 24.0).max.min(220.0).max(if (n <= 24) nameFit.max else 0)
    val labelW                   = (lanes.map(_.label.length * LabelChar + 28) :+ 110.0).max
    val rows                     = lanes.size + 1     // the clock lane first
    val bottom                   = Header + rows * RowHeight
    val width                    = (labelW + n * cw + 16).max(note.length * CaptionChar + 28)
    val height                   = bottom + (if (note.nonEmpty) 26 else 0) + 12
    val colW                     = f(cw)
    def x(i: Double)             = f(labelW + i * cw)
    def xy(i: Double, y: Double) = s"${x(i)},${f(y)}" // a point, at the start of cycle i
    def top(row: Int)            = Header + row * RowHeight + 7.0
    def low(row: Int)            = Header + (row + 1) * RowHeight - 7.0
    def text(at: String, y: Double, size: Int, fill: String, content: String, extra: String = "") =
      s"""<text x="$at" y="${f(y)}" font-size="$size" fill="$fill"$extra>${esc(content)}</text>"""
    def label(row: Int, s: String) = text("14", Header + row * RowHeight + 21, 13, "#2b3445", s)
    def shade(i: Int, y: Double, height: Double, fill: String, opacity: Double) =
      s"""<rect x="${x(i)}" y="$y" width="$colW" height="$height" fill="$fill" fill-opacity="$opacity"/>"""
    val out = new StringBuilder
    val w   = f(width)
    out ++= s"""<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$height" viewBox="0 0 $w $height" """
    out ++= """font-family="Inter, 'DejaVu Sans', sans-serif">"""
    out ++= s"""<rect x="0.5" y="0.5" width="${f(width - 1)}" height="${height - 1}" rx="8" fill="#ffffff" """
    out ++= """stroke="#d5dae3"/>"""
    // highlighted cycles, then a grid line at every clock edge
    for (i <- mark if i >= 0 && i < n) out ++= shade(i, 6, bottom - 6, "#f5a524", 0.22)
    for (i <- 0 to n)
      out ++= s"""<line x1="${x(i)}" y1="$Header" x2="${x(i)}" y2="$bottom" stroke="#e6e9f0"/>"""
    // cycle names, thinned out when the columns are narrow
    val every = math.max(1, math.ceil((nameFit.max + 4) / cw).toInt)
    for (i <- 0 until n if i % every == 0 || mark(i)) {
      val weight = if (mark(i)) "700" else "400"
      out ++= text(x(i + 0.5), 20, 11, "#5b6880", ns(i), s""" font-weight="$weight" text-anchor="middle"""")
    }
    def path(points: Seq[String], stroke: String, strokeWidth: Double) =
      out ++= s"""<path d="M${points.mkString(" L")}" fill="none" stroke="$stroke" stroke-width="$strokeWidth"/>"""
    // the clock: high in the first half of each cycle, rising at its end, where the registers take their values
    out ++= label(0, "clock")
    path(
      xy(0, low(0)) +: (0 until n).flatMap { i =>
        Seq(xy(i, top(0)), xy(i + 0.5, top(0)), xy(i + 0.5, low(0)), xy(i + 1, low(0)))
      } :+ xy(n, top(0)),
      "#7a8599",
      1.5
    )
    for (((lane, sample), k) <- lanes.zip(samples).zipWithIndex) {
      val row      = k + 1
      val (hi, lo) = (top(row), low(row))
      val mid      = (hi + lo) / 2
      out ++= label(row, lane.label)
      sample match {
        case Left(v) =>
          for (i <- 0 until n if v(i) != 0 && v(i) != 1) // x or z
            out ++= shade(i, hi, lo - hi, "#c9ced8", 0.6)
          val y = v.map(b => if (b == 1) hi else if (b == 0) lo else mid)
          path((0 until n).flatMap(i => Seq(xy(i, y(i)), xy(i + 1, y(i)))), "#1f6fb2", 2)
        case Right(bands) =>
          for ((s, k, t) <- bands) {
            val (a, z) = (labelW + s * cw + 2, labelW + (s + k) * cw - 2)
            if (t.isEmpty)
              out ++= s"""<line x1="${f(a)}" y1="${f(mid)}" x2="${f(z)}" y2="${f(
                  mid
                )}" stroke="#9aa4b5" stroke-width="1.5"/>"""
            else {
              val e      = math.min(5.0, (z - a) / 4)
              val points = Seq(a -> mid, a + e -> hi, z - e -> hi, z -> mid, z - e -> lo, a + e -> lo)
              out ++= s"""<polygon points="${points.map { case (px, py) => s"${f(px)},${f(py)}" }.mkString(" ")}" """
              out ++= """fill="#e8f1fb" stroke="#1f6fb2" stroke-width="1.5"/>"""
              val room  = ((z - a - 8) / CharWidth).toInt
              val shown = if (t.length <= room) t else if (room > 1) t.take(room - 1) + "…" else ""
              val font  = s""" font-family="$Mono" text-anchor="middle""""
              out ++= text(f((a + z) / 2), mid + 4, 12, "#1b2433", shown, font)
            }
          }
      }
    }
    if (note.nonEmpty) out ++= text("14", bottom + 22, 12, "#5b6880", note)
    out ++= "</svg>"
    out.toString
  }
}
