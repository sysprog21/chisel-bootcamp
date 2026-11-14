package riscv

/** The `.S` programs of the course, in directories of riscv/ such as `programs/src`: from sbt, which
  * runs in riscv/, or from a notebook, which runs in the repository's root (a directory is looked up
  * in the current directory first, then in riscv/).
  */
object Programs {

  private def resolve(dir: String): os.Path =
    Seq(os.pwd, os.pwd / "riscv")
      .map(_ / os.RelPath(dir))
      .find(os.isDir)
      .getOrElse(throw new IllegalArgumentException(s"no directory $dir, here or in riscv/"))

  private def files(dir: String, ext: String = "S"): Seq[os.Path] =
    os.list(resolve(dir)).filter(_.ext == ext).sortBy(_.baseName)

  /** The names of the programs in `dir`, sorted, without their extension `ext` (given without the dot). */
  def names(dir: String, ext: String = "S"): Seq[String] = files(dir, ext).map(_.baseName)

  /** The source of program `name` of `dir` (or its file with extension `ext`, such as `hex`). */
  def source(dir: String, name: String, ext: String = "S"): String = os.read(resolve(dir) / s"$name.$ext")

  /** Each program of `dir`, by name, assembled (an error names the program that does not assemble). */
  def images(dir: String): Seq[(String, Seq[Long])] = files(dir).map { f =>
    try f.baseName -> Asm(os.read(f))
    catch { case e: IllegalArgumentException => throw new IllegalArgumentException(s"$f: ${e.getMessage}", e) }
  }

  /** The programs a core is checked with, by name, assembled: those of the course (`programs/src`,
    * but `hello`, which needs the UART of Module 5.8), a random program for each of `seeds` (named
    * `RandomProgram(seed)`), and the official rv32ui tests.
    */
  def corpus(seeds: Seq[Long]): Seq[(String, Seq[Long])] =
    images("programs/src").filterNot(_._1 == "hello") ++
      seeds.map(s => s"RandomProgram($s)" -> Asm(RandomProgram(s))) ++ images("programs/riscv-tests/rv32ui")
}
