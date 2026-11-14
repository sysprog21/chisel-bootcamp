#!/usr/bin/env python3
"""Import the rv32ui tests of riscv-tests into riscv/programs/riscv-tests/rv32ui/.

Usage: tools/import-riscv-tests.py           write the files
       tools/import-riscv-tests.py --check   fail if the committed files differ from a new import

The tests are assembly with C preprocessor macros, and the course has no RISC-V compiler (its
assembler is riscv.Asm), so this script, which needs git and GCC's C preprocessor (cpp; the
committed files come from GCC 13, as on CI's Ubuntu 24.04), expands them once against
riscv/programs/riscv-tests/riscv_test.h, a test environment without CSRs. The expanded files are
committed; riscv.Asm assembles them in the Docker image.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import time

REPOSITORY = "https://github.com/riscv-software-src/riscv-tests.git"
REVISION = "3df703cd52d5f0d8224e3c50bf03c6cb70efff98"  # 2026-10-19
# Tests left out, and why
EXCLUDED = {
    "fence_i": "FENCE.I belongs to Zifencei, not RV32I",
    "ma_data": "misaligned accesses halt the course's cores (no trap handler without CSRs)",
}

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TESTS = os.path.join(ROOT, "riscv", "programs", "riscv-tests")


def run(*cmd, **kw):
    result = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if result.returncode != 0:
        sys.exit(f"{' '.join(cmd)} failed:\n{result.stderr}")
    return result.stdout


def generate(out):
    """Write the expanded tests and the license into the directory `out`."""
    with tempfile.TemporaryDirectory() as tmp:
        repo = os.path.join(tmp, "riscv-tests")
        run("git", "init", "-q", repo)
        for attempt in range(3):  # the network may hiccup
            fetch = subprocess.run(["git", "-C", repo, "fetch", "-q", "--depth", "1", REPOSITORY, REVISION])
            if fetch.returncode == 0:
                break
            time.sleep(5 * (attempt + 1))
        else:
            sys.exit(f"cannot fetch riscv-tests {REVISION}")
        run("git", "-C", repo, "checkout", "-q", "FETCH_HEAD")
        source = os.path.join(repo, "isa", "rv32ui")
        staged = os.path.join(tmp, "rv32ui")  # replaces out/rv32ui once every test has expanded
        os.makedirs(staged)
        for name in sorted(f[:-2] for f in os.listdir(source) if f.endswith(".S")):
            if name in EXCLUDED:
                continue
            expanded = run(
                "cpp",
                "-P",
                "-x",
                "assembler-with-cpp",
                "-D__riscv_xlen=32",
                "-I",
                TESTS,
                "-I",
                os.path.join(repo, "isa", "macros", "scalar"),
                f"{name}.S",
                cwd=source,
            )
            # without the comments of the macro files (they describe other extensions too)
            lines = [l.rstrip() for l in expanded.splitlines() if l.strip() and not l.lstrip().startswith("#")]
            header = (
                f"# riscv-tests {REVISION[:12]}, isa/rv32ui/{name}.S, expanded by tools/import-riscv-tests.py\n"
                "# against riscv/programs/riscv-tests/riscv_test.h. Do not edit; see ../LICENSE.\n"
            )
            with open(os.path.join(staged, f"{name}.S"), "w") as f:
                f.write(header + "\n".join(lines) + "\n")
        shutil.rmtree(os.path.join(out, "rv32ui"), ignore_errors=True)  # no stale tests
        shutil.move(staged, os.path.join(out, "rv32ui"))
        shutil.copy(os.path.join(repo, "LICENSE"), os.path.join(out, "LICENSE"))
        with open(os.path.join(out, "EXCLUDED"), "w") as f:
            f.writelines(f"{name}: {why}\n" for name, why in sorted(EXCLUDED.items()))


if __name__ == "__main__":
    if sys.argv[1:] not in ([], ["--check"]):
        sys.exit(__doc__)
    if sys.argv[1:] == ["--check"]:
        with tempfile.TemporaryDirectory() as fresh:
            generate(fresh)
            diff = subprocess.run(["diff", "-r", "-x", "riscv_test.h", fresh, TESTS], capture_output=True, text=True)
            if diff.returncode != 0:
                sys.exit(f"riscv/programs/riscv-tests differs from a new import:\n{diff.stdout}")
            print("riscv/programs/riscv-tests matches a new import")
    else:
        generate(TESTS)
        print(f"imported riscv-tests {REVISION[:12]} into {os.path.relpath(TESTS, ROOT)}")
