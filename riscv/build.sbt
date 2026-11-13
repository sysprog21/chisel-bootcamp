// The RISC-V processor of Module 5. Versions must agree with the Dockerfile and
// source/load-ivy.sc (tools/check-versions.py checks them).
scalaVersion := "2.13.18"

val chiselVersion = "7.16.0"

libraryDependencies ++= Seq(
  "org.chipsalliance" %% "chisel"    % chiselVersion,
  "org.scalatest"     %% "scalatest" % "3.2.19" % Test
)
addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full)
scalacOptions ++= Seq("-deprecation", "-feature", "-language:reflectiveCalls")
// ChiselSim tests build Verilator models; run them one at a time
Test / parallelExecution := false
