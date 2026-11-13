#!/usr/bin/env python3
"""Print, as a JSON list, the notebooks that CI runs.

Usage: tools/ci-notebooks.py all
       git diff --name-only BASE...HEAD | tools/ci-notebooks.py changed

`all` lists every notebook registered in runtest.py; pushes to dev and the nightly run use it.
`changed` is for pull requests: when every changed file is a notebook or cannot affect how the
notebooks run (documentation, images, tools, tests), it lists the smoke subset plus the changed
notebooks; otherwise (the Dockerfile, `source/`, `runtest.py`, a workflow, ...) it lists every
notebook, since any of them may break.
"""
import fnmatch
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from runtest import notebooks  # noqa: E402

# One notebook per part of the toolchain: the kernel and Chisel (0_demo, 2.1), ChiselSim and
# Verilator (2.6), and the FIRRTL modules (4.1).
SMOKE = ["0_demo.ipynb", "2.1_first_module.ipynb", "2.6_chiselsim.ipynb", "4.1_firrtl_ast.ipynb"]
# Files that cannot change the outcome of runtest.py: they are not in the image, are only
# rendered, or are tested by jobs that always run (tests/test_visualizer.py).
INERT = ["*.md", "LICENSE", "images/*", "binder/*", "tools/*", "tests/*", ".pre-commit-config.yaml",
         ".github/dependabot.yml", ".github/workflows/checks.yml"]


def select(mode, changed):
    if mode == "all" or any(p not in notebooks and not any(fnmatch.fnmatch(p, g) for g in INERT)
                            for p in changed):
        return sorted(notebooks)
    return sorted((set(SMOKE) | set(changed)) & set(notebooks))


if __name__ == "__main__":
    if sys.argv[1:] not in (["all"], ["changed"]):
        sys.exit(__doc__)
    # changed files come one per line on stdin: no argument-length limit and no shell quoting
    changed = [line.strip() for line in sys.stdin if line.strip()] if sys.argv[1] == "changed" else []
    print(json.dumps(select(sys.argv[1], changed)))
