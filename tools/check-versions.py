#!/usr/bin/env python3
"""Check that the pinned tool versions agree across the files that repeat them.

The Dockerfile `ENV`s are the reference. Dependabot cannot bump them and `load-ivy.sc`
needs literal strings, so the versions are repeated; this script fails on any mismatch.
It also checks that FIRTOOL_VERSION is the firtool version Chisel was published against
(`chisel3.BuildInfo.firtoolVersion`), read from the Chisel sources jar.

Usage: tools/check-versions.py            check every version
       tools/check-versions.py --print KEY  print one version (e.g. chisel) as the Dockerfile has it

The Chisel sources jar is looked up in the coursier caches, otherwise downloaded from Maven Central.
"""

import argparse
import io
import os
import re
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAVEN = "https://repo1.maven.org/maven2"
CHISEL_SOURCES = "org/chipsalliance/chisel_2.13/{v}/chisel_2.13-{v}-sources.jar"

# (file, version key, regular expression whose group 1 is the version). A file may list a key
# several times: every occurrence must match. Each pattern must match at least once.
V = r"([0-9]+(?:\.[0-9]+)+)"
VP = r"([0-9]+(?:\.[0-9]+)+(?:\.post[0-9]+)?)"  # a PyPI version, which may have a .postN suffix
SHA256 = r"([0-9a-f]{64})"
CHECKS = [
    ("Dockerfile", "scala", rf"^ENV SCALA_VERSION={V}$"),
    ("Dockerfile", "almond", rf"^ENV ALMOND_VERSION={V}$"),
    ("Dockerfile", "coursier", rf"^ENV COURSIER_VERSION={V}$"),
    ("Dockerfile", "chisel", rf"^ENV CHISEL_VERSION={V}$"),
    ("Dockerfile", "firtool", rf"^ENV FIRTOOL_VERSION={V}$"),
    ("Dockerfile", "scalatest", rf"org\.scalatest:scalatest_2\.13:{V}"),
    ("Dockerfile", "sbt", rf"^ENV SBT_VERSION={V}$"),
    ("riscv/project/build.properties", "sbt", rf"^sbt\.version={V}$"),
    ("riscv/build.sbt", "scala", rf'^scalaVersion := "{V}"'),
    ("riscv/build.sbt", "chisel", rf'val chiselVersion = "{V}"'),
    ("riscv/build.sbt", "scalatest", rf'"scalatest"\s+%\s+"{V}"'),
    ("source/load-ivy.sc", "chisel", rf"chisel-plugin:{V}`"),
    ("source/load-ivy.sc", "chisel", rf"org\.chipsalliance::chisel:{V}`"),
    ("source/load-ivy.sc", "scalatest", rf"org\.scalatest::scalatest:{V}`"),
    ("tools/install.sh", "scala", rf"^SCALA_VERSION={V}$"),
    ("tools/install.sh", "almond", rf"^ALMOND_VERSION={V}$"),
    ("tools/install.sh", "coursier", rf"^COURSIER_VERSION={V}$"),
    ("tools/install.sh", "sbt", rf"^SBT_VERSION={V}$"),
    ("Dockerfile", "sbt-sha256", rf"^ENV SBT_SHA256={SHA256}$"),
    ("tools/install.sh", "sbt-sha256", rf"^SBT_SHA256={SHA256}$"),
    ("Dockerfile", "coursier-sha256", rf"^ENV COURSIER_SHA256={SHA256}$"),
    ("tools/install.sh", "coursier-sha256", rf"^COURSIER_SHA256={SHA256}$"),
    ("binder/postBuild", "coursier-sha256", rf"^COURSIER_SHA256={SHA256}$"),
    ("tools/install.sh", "firtool", rf"^FIRTOOL_VERSION={V}$"),
    ("Dockerfile", "firtool-jar-sha256", rf"^ENV FIRTOOL_JAR_SHA256={SHA256}$"),
    ("tools/install.sh", "firtool-jar-sha256", rf"^FIRTOOL_JAR_SHA256={SHA256}$"),
    ("Dockerfile", "yowasp-yosys", rf"yowasp-yosys=={VP}"),
    ("tools/install.sh", "yowasp-yosys", rf"^YOWASP_YOSYS_VERSION={VP}$"),
    ("Dockerfile", "yowasp-runtime", rf"yowasp-runtime=={V}"),
    ("tools/install.sh", "yowasp-runtime", rf"^YOWASP_RUNTIME_VERSION={V}$"),
    ("Dockerfile", "wasmtime", rf"wasmtime=={V}"),
    ("tools/install.sh", "wasmtime", rf"^WASMTIME_VERSION={V}$"),
    # the oldest Verilator that works with ChiselSim (older ones abort, see docs/troubleshooting.md)
    ("Dockerfile", "verilator-min", rf"Verilator {V} or newer"),
    ("tools/install.sh", "verilator-min", rf"^VERILATOR_MIN={V}$"),
    ("binder/postBuild", "verilator-min", rf"^VERILATOR_MIN={V}$"),
    ("binder/environment.yml", "verilator-min", rf"verilator >={V}"),
    ("Install.md", "verilator-min", rf"Verilator {V} or newer"),
    ("docs/troubleshooting.md", "verilator-min", rf"Verilator {V} or newer"),
    ("Install.md", "coursier", rf"coursier/releases/download/v{V}/coursier"),
    ("Install.md", "coursier-sha256", rf"^echo \"{SHA256}  coursier\" \| sha256sum -c -$"),
    ("binder/postBuild", "scala", rf"^SCALA_VERSION={V}$"),
    ("binder/postBuild", "almond", rf"^ALMOND_VERSION={V}$"),
    ("binder/postBuild", "coursier", rf"^COURSIER_VERSION={V}$"),
    ("Install.md", "scala", rf"Scala {V} with Almond"),
    ("Install.md", "almond", rf"with Almond {V} kernel"),
    ("Install.md", "chisel", rf"Chisel {V} and CIRCT"),
    ("Install.md", "firtool", rf"CIRCT firtool {V}"),
    ("Install.md", "sbt", rf"^- sbt {V} with"),
    ("Install.md", "firtool", rf"contains `firtool` {V}"),
    ("Install.md", "scala", rf"use Scala {V}\)"),
    ("Install.md", "scala", rf"scala-kernel_{V}:"),
    ("Install.md", "almond", r"scala-kernel_[0-9.]+:([0-9.]+[0-9])"),
    ("README.md", "chisel", rf"`chisel-{V}`"),
    ("docs/jupyterhub/Dockerfile", "chisel", rf"^ARG BOOTCAMP_IMAGE=sysprog21/chisel-bootcamp:chisel-{V}$"),
    ("2.8_memories.ipynb", "firtool", rf"firtool {V} builds"),  # its read-under-write findings
    ("2.8_memories.ipynb", "firtool", rf"version {V} here"),
    ("2.8_memories.ipynb", "chisel", rf"In Chisel {V}, `SRAM`"),  # its MemoryFile finding
]
REFERENCE_FILE = "Dockerfile"


def collect():
    """:returns {key: [(file, line number, version)]} and a list of patterns that matched nothing."""
    found, missing, files = {}, [], {}
    for path, key, pattern in CHECKS:
        if path not in files:
            with open(os.path.join(ROOT, path), encoding="utf-8") as f:
                files[path] = f.read().splitlines()
        lines = files[path]
        hits = [(path, n, m.group(1)) for n, line in enumerate(lines, 1) for m in re.finditer(pattern, line)]
        if not hits:
            missing.append(f"{path}: no match for {key} ({pattern})")
        found.setdefault(key, []).extend(hits)
    return found, missing


def chisel_firtool_version(chisel_version):
    """:returns the firtool version in chisel3.BuildInfo, read from the Chisel sources jar."""
    rel = CHISEL_SOURCES.format(v=chisel_version)
    caches = [os.environ.get("COURSIER_CACHE"), os.path.expanduser("~/.cache/coursier/v1"), "/opt/cache/coursier"]
    cached = [
        p for p in (os.path.join(d, "https/repo1.maven.org/maven2", rel) for d in caches if d) if os.path.isfile(p)
    ]
    if cached:
        with open(cached[0], "rb") as f:
            data = f.read()
    else:
        with urllib.request.urlopen(f"{MAVEN}/{rel}", timeout=60) as r:
            data = r.read()
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        name = next((n for n in z.namelist() if n.endswith("chisel3/BuildInfo.scala")), None)
        source = z.read(name).decode("utf-8") if name else ""
    m = re.search(r'firtoolVersion\s*:\s*scala\.Option\[String\]\s*=\s*Some\("([^"]+)"\)', source)
    if not m:
        sys.exit("error: firtoolVersion not found in chisel3/BuildInfo.scala of the Chisel sources jar")
    return m.group(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--print", metavar="KEY", help="print one version and exit")
    args = parser.parse_args()

    found, errors = collect()
    if errors:  # a pattern matched nothing: its file changed shape, so update CHECKS
        print("\n".join(errors), file=sys.stderr)
        return 1
    reference = {k: next((v for f, _, v in hits if f == REFERENCE_FILE), None) for k, hits in found.items()}
    if args.print:
        if not reference.get(args.print):
            sys.exit(f"error: no {args.print} version in {REFERENCE_FILE}")
        print(reference[args.print])
        return 0
    for key, hits in found.items():
        errors += [
            f"{path}:{line}: {key} {version}, but {REFERENCE_FILE} has {reference[key]}"
            for path, line, version in hits
            if version != reference[key]
        ]

    expected = chisel_firtool_version(reference["chisel"])
    if expected != reference["firtool"]:
        errors.append(
            f"{REFERENCE_FILE}: FIRTOOL_VERSION {reference['firtool']}, but Chisel "
            f"{reference['chisel']} was published against firtool {expected}"
        )

    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("versions agree: " + ", ".join(f"{k} {v}" for k, v in sorted(reference.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
