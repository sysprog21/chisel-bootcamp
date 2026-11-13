package riscv

import java.io.File
import scala.io.Source
import scala.util.Using

/** The `.S` programs of a directory under riscv/ (the tests run from there). */
object TestPrograms {

  /** The names (without `.S`) of the programs in `dir`, sorted; none if it does not exist. */
  def names(dir: String): Seq[String] =
    Option(new File(dir).list())
      .getOrElse(Array.empty[String])
      .filter(_.endsWith(".S"))
      .sorted
      .map(_.stripSuffix(".S"))
      .toSeq

  def source(dir: String, name: String): String =
    Using.resource(Source.fromFile(new File(dir, s"$name.S")))(_.mkString)
}
