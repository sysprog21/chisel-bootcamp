#!/usr/bin/env python3
"""Format the Scala code with scalafmt (and .scalafmt.conf): source files and the notebooks' code cells.

Usage: tools/format-scala.py [--check] [--scalafmt PATH] [NOTEBOOK...]

The source files (source/*.sc, riscv/) are formatted in place (with --check, only checked). Every code cell is
written to a temporary `.sc` file and all of them are formatted by one scalafmt run. A cell that
scalafmt cannot format is left as it is; UNFORMATTABLE lists how many such cells each notebook is
expected to have (cells that show a syntax error on purpose), and a different number is an error.
With --check, nothing is written and the exit status is 1 if a cell would change.
"""

import argparse
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# notebook -> number of code cells that scalafmt cannot format
UNFORMATTABLE = {"3.6_types.ipynb": 1}  # `case (0.U) =>`: a pattern cannot be a hardware literal


def code_cells(nb):
    return [c for c in nb["cells"] if c["cell_type"] == "code" and "".join(c["source"]).strip()]


def to_lines(text):
    lines = text.split("\n")
    return [line + "\n" for line in lines[:-1]] + [lines[-1]]


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--check", action="store_true", help="report cells that would change, write nothing")
    parser.add_argument("--scalafmt", default="scalafmt", help="scalafmt executable")
    parser.add_argument("notebooks", nargs="*", help="notebooks to format (default: all, and the source files)")
    args = parser.parse_args()
    config = ["--config", os.path.join(ROOT, ".scalafmt.conf"), "--non-interactive"]

    def scalafmt(*argv, **kw):
        try:
            return subprocess.run([args.scalafmt, *config, *argv], **kw)
        except FileNotFoundError:
            sys.exit(f"scalafmt not found ({args.scalafmt}); see CONTRIBUTING.md, or pass --scalafmt PATH")

    # The source files (only when formatting everything): the notebooks' setup script and riscv/
    patterns = ["source/*.sc", "riscv/*.sbt", "riscv/src/**/*.scala", "riscv/solutions/**/*.scala"]
    scripts = (
        [] if args.notebooks else sorted(f for p in patterns for f in glob.glob(os.path.join(ROOT, p), recursive=True))
    )
    mode = ["--test"] if args.check else []
    scripts_ok = not scripts or scalafmt(*mode, *scripts, cwd=ROOT).returncode == 0

    paths = sorted({os.path.abspath(p) for p in args.notebooks} or glob.glob(os.path.join(ROOT, "*.ipynb")))

    notebooks = {}
    with tempfile.TemporaryDirectory() as tmp:
        files = {}  # temporary file name -> (notebook path, cell)
        for path in paths:
            with open(path, encoding="utf-8") as f:
                notebooks[path] = json.load(f)
            for cell in code_cells(notebooks[path]):
                name = f"{len(files):05d}.sc"
                with open(os.path.join(tmp, name), "w", encoding="utf-8", newline="\n") as f:
                    f.write("".join(cell["source"]) + "\n")
                files[name] = (path, cell)
        # One run for every cell, from the temporary directory: scalafmt only formats files below
        # its working directory. It names each file it fails on (a parse error or a crash).
        run = scalafmt(*files, cwd=tmp, capture_output=True, text=True)
        # scalafmt names a file it fails on as "FailedToFormat: <path>" (a crash) or "<path>:<line>: error"
        failures_found = re.findall(
            r"FailedToFormat: \S*?(\d{5}\.sc)\b|(\d{5}\.sc):\d+: error", run.stdout + run.stderr
        )
        failed = {a or b for a, b in failures_found} & set(files)
        if run.returncode != 0 and not failed:
            sys.exit(f"scalafmt failed (exit status {run.returncode}):\n{run.stdout}{run.stderr}")
        changed = set()
        for name, (path, cell) in files.items():
            if name in failed:
                continue
            with open(os.path.join(tmp, name), encoding="utf-8") as f:
                formatted = f.read().rstrip("\n")
            if formatted != "".join(cell["source"]):
                changed.add(path)
                cell["source"] = to_lines(formatted)

    failures = Counter(os.path.basename(files[f][0]) for f in failed)
    unexpected = [
        f"{name}: scalafmt cannot format {failures[name]} cell(s), expected {UNFORMATTABLE.get(name, 0)}"
        for name in sorted({os.path.basename(p) for p in paths})
        if failures[name] != UNFORMATTABLE.get(name, 0)
    ]
    if unexpected:
        print("\n".join(unexpected) + "\n" + run.stdout + run.stderr, file=sys.stderr)
    for path in sorted(changed):
        rel = os.path.relpath(path, ROOT)
        if args.check:
            print(f"{rel}: Scala cells are not formatted (run tools/format-scala.py)", file=sys.stderr)
        else:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                json.dump(notebooks[path], f, indent=1, ensure_ascii=False)
                f.write("\n")
            print(f"formatted {rel}")
    return 1 if unexpected or not scripts_ok or (args.check and changed) else 0


if __name__ == "__main__":
    sys.exit(main())
