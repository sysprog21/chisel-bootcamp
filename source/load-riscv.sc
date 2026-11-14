// Makes the classes of the riscv/ sbt project (Module 5) available in a notebook. Load it after
// source/load-ivy.sc:
//
//   interp.load.module(os.pwd / "source" / "load-ivy.sc")
//   interp.load.module(os.pwd / "source" / "load-riscv.sc")
//
// and then `import riscv._` in a later cell. The project is compiled with sbt when its sources
// changed since the last compilation (the first time this takes about half a minute).
// A running kernel keeps the classes it has already loaded: after editing riscv/src, restart the
// kernel and load the two files again.

val riscvDir     = os.pwd / "riscv"
val riscvClasses = riscvDir / "target" / "scala-2.13" / "classes"

// What the compiled classes depend on: the build definition and the main sources. Comparing a
// list of (path, size, time) also notices deleted and renamed files.
def riscvFingerprint: String = {
  val project = os.walk(riscvDir / "project", skip = _.last == "target").filter(os.isFile)
  val files   = Seq(riscvDir / "build.sbt") ++ project ++ os.walk(riscvDir / "src" / "main").filter(os.isFile)
  files.sorted.map(f => s"${f.relativeTo(riscvDir)} ${os.size(f)} ${os.mtime(f)}").mkString("\n")
}

os.makeDir.all(riscvDir / "target")
// Two notebooks that start at the same time must not run sbt in the same directory at once
scala.util
  .Using(
    java.nio.channels.FileChannel.open(
      (riscvDir / "target" / ".load-riscv.lock").toNIO,
      java.nio.file.StandardOpenOption.CREATE,
      java.nio.file.StandardOpenOption.WRITE
    )
  ) { lockFile =>
    lockFile.lock() // released when the channel is closed
    val stamp       = riscvDir / "target" / ".load-riscv.stamp"
    val fingerprint = riscvFingerprint // taken before sbt runs: an edit during the compile counts next time
    if (!os.exists(riscvClasses) || !os.exists(stamp) || os.read(stamp) != fingerprint) {
      println("Compiling riscv/ with sbt ...")
      val sbt = os.proc("sbt", "-batch", "compile").call(cwd = riscvDir, check = false, mergeErrIntoOut = true)
      if (sbt.exitCode != 0) {
        println(sbt.out.text())
        throw new RuntimeException("sbt could not compile riscv/ (see the output above)")
      }
      os.write.over(stamp, fingerprint)
    }
  }
  .get

interp.load.cp(riscvClasses)
