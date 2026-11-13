#!/usr/bin/env python3
"""Run the bootcamp notebooks in the Scala (Almond) kernel and check their errors.

Many cells are exercises (they contain `???`) or demonstrate an error on purpose, so a
notebook is not expected to run without errors. Each notebook below lists the errors that
are expected, in order; the run fails if the notebook reports anything else.

Usage: runtest.py [notebook_name.ipynb] [notebook_name_2.ipynb] [...]
"""

import itertools
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import time

from typing import Dict, List, Tuple

ROOT = os.path.dirname(os.path.abspath(__file__))
# The first cell downloads and compiles dependencies, each `simulate` runs Verilator.
CELL_TIMEOUT = int(os.environ.get("BOOTCAMP_CELL_TIMEOUT", "600"))

NOT_IMPLEMENTED = "scala.NotImplementedError"
# A deprecated API makes the kernel print a deprecation warning (see `source/load-ivy.sc`); it is
# reported as an error so that deprecations do not creep back in. Notebooks must therefore not
# print the word themselves.
DEPRECATION = re.compile(r"^.*\bdeprecat.*$", re.IGNORECASE | re.MULTILINE)
COMPILATION_FAILED = "Compilation Failed"


def _notebook_run(path: str) -> List[Tuple[int, str]]:
    """Execute every code cell of a notebook in a fresh kernel.

    :returns the errors as a list of (cell index, "<error name>: <error value>").
    """
    # Imported here so that other scripts can import `notebooks` without Jupyter installed
    from jupyter_client.manager import start_new_kernel

    with open(path, encoding="utf-8") as f:
        nb = json.load(f)

    # The notebooks load `source/load-ivy.sc` relative to the working directory.
    os.chdir(ROOT)
    # The kernel logs the stack trace of every error to its stderr; they are checked below.
    km, kc = start_new_kernel(
        kernel_name="scala", startup_timeout=300, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
    )
    errors: List[Tuple[int, str]] = []
    try:
        for index, cell in enumerate(nb["cells"]):
            if cell["cell_type"] != "code":
                continue
            source = "".join(cell["source"])
            if not source.strip():
                continue
            msg_id = kc.execute(source)
            timed_out = False
            deadline = time.monotonic() + CELL_TIMEOUT  # for the whole cell, however chatty it is
            while True:
                try:
                    msg = kc.get_iopub_msg(timeout=max(0.0, deadline - time.monotonic()))
                except queue.Empty:
                    errors.append((index, "Timeout: cell did not finish"))
                    km.interrupt_kernel()
                    timed_out = True
                    break
                if msg["parent_header"].get("msg_id") != msg_id:
                    continue
                if msg["msg_type"] == "stream":
                    errors += [
                        (index, "Deprecation: " + m.group(0).strip())
                        for m in DEPRECATION.finditer(msg["content"]["text"])
                    ]
                elif msg["msg_type"] == "error":
                    content = msg["content"]
                    errors.append((index, "{}: {}".format(content["ename"], content["evalue"])))
                elif msg["msg_type"] == "status" and msg["content"]["execution_state"] == "idle":
                    break
            if timed_out:  # the kernel state can no longer be trusted: don't wait for the next cells
                break
    finally:
        kc.stop_channels()
        km.shutdown_kernel(now=True)
    return errors


def check_errors(file_name: str, expected: List[str], actual: List[Tuple[int, str]]) -> bool:
    """When errors occur they are due to a mismatch between the errors that occurred at
    runtime and the errors that are expected, which are defined below.
    Look at what was expected and either fix the notebook or add the text of the error that
    occurred.

    :param file_name: name of file where errors occurred
    :param expected:  errors that were expected
    :param actual:    errors that occurred: (cell index, error).
    :return: True if the actual errors are exactly the expected ones
    """
    return_value = True
    for i, (e, a) in enumerate(itertools.zip_longest(expected, actual, fillvalue=None)):
        if e is None or a is None or e not in a[1]:
            if return_value:
                print("=" * 100, file=sys.stderr)
                print(f"Errors detected in {file_name}", file=sys.stderr)
            got = "-- No Error --" if a is None else f"cell {a[0]}: {a[1][:100]!r}"
            print(f"No match for {i}-th expected error {e if e else '-- No Error --'!r}, got {got}", file=sys.stderr)
            return_value = False
    if not return_value:
        print("=" * 100, file=sys.stderr)
    return return_value


notebooks: Dict[str, List[str]] = {
    # This is the list of pages to try and run along with each page's expected errors.
    # (a substring of "<error name>: <error value>" per error, in order of occurrence)
    "0_demo.ipynb": [],
    "1_intro_to_scala.ipynb": [],
    "2.1_first_module.ipynb": [NOT_IMPLEMENTED],
    "2.2_comb_logic.ipynb": [COMPILATION_FAILED] + [NOT_IMPLEMENTED] * 3,
    "2.3_control_flow.ipynb": [NOT_IMPLEMENTED] * 2 + [COMPILATION_FAILED] + [NOT_IMPLEMENTED] + [COMPILATION_FAILED],
    "2.4_sequential_logic.ipynb": [NOT_IMPLEMENTED] * 2,
    "2.5_exercise.ipynb": [NOT_IMPLEMENTED] * 3,
    "2.6_chiselsim.ipynb": [],
    "2.7_enums_and_bundles.ipynb": [NOT_IMPLEMENTED] * 3,
    "2.8_memories.ipynb": [NOT_IMPLEMENTED] * 2,
    "3.1_parameters.ipynb": ["java.util.NoSuchElementException"],
    "3.2_collections.ipynb": [NOT_IMPLEMENTED],
    "3.2_interlude.ipynb": [],
    "3.3_higher-order_functions.ipynb": [NOT_IMPLEMENTED]
    + ["java.lang.UnsupportedOperationException"]
    + [NOT_IMPLEMENTED] * 3,
    "3.4_functional_programming.ipynb": [NOT_IMPLEMENTED, COMPILATION_FAILED, NOT_IMPLEMENTED, COMPILATION_FAILED],
    "3.5_object_oriented_programming.ipynb": [],
    "3.6_types.ipynb": ["ChiselException: Connection between sink"]
    + ["I give up!"]
    + ['expected ")"']
    + [NOT_IMPLEMENTED] * 2
    + [COMPILATION_FAILED] * 4,
    "4.1_firrtl_ast.ipynb": [NOT_IMPLEMENTED],
    "4.2_firrtl_ast_traversal.ipynb": [NOT_IMPLEMENTED],
    "4.3_firrtl_common_idioms.ipynb": [NOT_IMPLEMENTED],
    "4.4_firrtl_add_ops_per_module.ipynb": [NOT_IMPLEMENTED],
    "5.1_riscv_isa.ipynb": [NOT_IMPLEMENTED],
    "5.2_alu_and_regfile.ipynb": [NOT_IMPLEMENTED] * 2,
    "5.3_decoder.ipynb": [NOT_IMPLEMENTED] * 4,
    "5.4_single_cycle.ipynb": [NOT_IMPLEMENTED] * 5,
    "5.5_compliance.ipynb": [NOT_IMPLEMENTED],
    "5.6_pipeline.ipynb": [NOT_IMPLEMENTED] * 5,
    "5.7_hazards.ipynb": [NOT_IMPLEMENTED] * 4,
    "5.8_bus_and_mmio.ipynb": [NOT_IMPLEMENTED] * 3,
    # without Yosys, the formal check of the `Forward` exercise prints a note instead of building it
    "5.9_debug_and_verify.ipynb": [NOT_IMPLEMENTED]
    * (6 if shutil.which("yosys") or shutil.which("yowasp-yosys") else 5),
}

if __name__ == "__main__":
    if len(sys.argv) > 1:
        if sys.argv[1] in ("--help", "-h"):
            print(__doc__)
            sys.exit(0)
        notebooks_to_run = sys.argv[1:]
    else:
        notebooks_to_run = sorted(notebooks)  # all notebooks
    failed = False
    notebooks_to_run = [os.path.basename(n) for n in notebooks_to_run]
    unknown = [n for n in notebooks_to_run if n not in notebooks]
    if unknown:
        print("Unknown notebook(s): " + ", ".join(unknown), file=sys.stderr)
        sys.exit(2)
    for n in notebooks_to_run:
        print(f"Running {n} ...", flush=True)
        actual = _notebook_run(os.path.join(ROOT, n))
        if not check_errors(n, notebooks[n], actual):
            failed = True
    sys.exit(1 if failed else 0)
