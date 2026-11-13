#!/usr/bin/env python3
"""Print, as a JSON list, the notebooks that CI runs.

Usage: tools/ci-notebooks.py all
       git diff --name-only BASE...HEAD | tools/ci-notebooks.py changed

`all` lists every notebook registered in runtest.py; pushes to dev and the nightly run use it.
`changed` is for pull requests: when every changed file is a notebook or cannot affect how the
notebooks run (documentation, images, tools, tests), it lists the smoke subset plus the changed
notebooks, plus the notebooks that use riscv/ when what they read from it changed; otherwise (the
Dockerfile, `source/`, `runtest.py`, a workflow, ...) it lists every notebook, since any of them may
break.
"""

import fnmatch
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
from runtest import notebooks  # noqa: E402

# One notebook per part of the toolchain: the kernel and Chisel (0_demo, 2.1), ChiselSim and
# Verilator (2.6), and the FIRRTL modules (4.1).
SMOKE = ["0_demo.ipynb", "2.1_first_module.ipynb", "2.6_chiselsim.ipynb", "4.1_firrtl_ast.ipynb"]
# Files that cannot change the outcome of runtest.py: they are not in the image, are only
# rendered, or are tested by other jobs (tests/test_visualizer.py always; riscv/ by the riscv job).
INERT = [
    "*.md",
    "riscv/*",
    "LICENSE",
    "images/*",
    "binder/*",
    "tools/*",
    "tests/*",
    ".pre-commit-config.yaml",
    ".scalafmt.conf",
    "pyproject.toml",
    ".github/dependabot.yml",
    ".github/workflows/checks.yml",
]
# What the notebooks that load source/load-riscv.sc use from riscv/: what source/load-riscv.sc compiles, and the program
# images and the sources that 5.4 assembles (the rest of riscv/ is inert)
RISCV_INPUTS = [
    "riscv/build.sbt",
    "riscv/project/*",
    "riscv/src/main/*",
    "riscv/programs/hex/*",
    "riscv/programs/src/*",
    "riscv/programs/riscv-tests/*",
]


def uses_riscv(notebook):
    with open(os.path.join(ROOT, notebook), encoding="utf-8") as f:
        return "load-riscv.sc" in f.read()


def select(mode, changed):
    if mode == "all" or any(p not in notebooks and not any(fnmatch.fnmatch(p, g) for g in INERT) for p in changed):
        return sorted(notebooks)
    selected = set(SMOKE) | set(changed)
    if any(fnmatch.fnmatch(p, g) for p in changed for g in RISCV_INPUTS):
        selected |= {n for n in notebooks if uses_riscv(n)}
    return sorted(selected & set(notebooks))


if __name__ == "__main__":
    if sys.argv[1:] not in (["all"], ["changed"]):
        sys.exit(__doc__)
    # changed files come one per line on stdin: no argument-length limit and no shell quoting
    changed = [line.strip() for line in sys.stdin if line.strip()] if sys.argv[1] == "changed" else []
    print(json.dumps(select(sys.argv[1], changed)))
