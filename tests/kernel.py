"""A Scala kernel for the tests in this directory, which run from the repository root."""

import contextlib
import queue
import subprocess
import time

from jupyter_client.manager import start_new_kernel


@contextlib.contextmanager
def scala_kernel(timeout=600):
    """Yields `run(code)`, which executes `code`, echoes and returns its text output, and raises on
    an error or when `code` runs longer than `timeout` seconds. The kernel is shut down afterwards."""
    km, kc = start_new_kernel(
        kernel_name="scala",
        startup_timeout=300,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    def run(code):
        mid = kc.execute(code)
        out = []
        deadline = time.monotonic() + timeout  # for the whole cell, however chatty it is
        while True:
            try:
                m = kc.get_iopub_msg(timeout=max(0.0, deadline - time.monotonic()))
            except queue.Empty:
                raise TimeoutError(f"no result after {timeout} s:\n{code}") from None
            if m["parent_header"].get("msg_id") != mid:
                continue
            if m["msg_type"] == "stream":
                print(m["content"]["text"], end="")
                out.append(m["content"]["text"])
            if m["msg_type"] == "error":
                # the kernel's stderr, which also has the stack trace, is discarded
                raise RuntimeError(
                    "\n".join(m["content"].get("traceback", []))
                    or m["content"]["ename"] + ": " + m["content"]["evalue"]
                )
            if m["msg_type"] == "status" and m["content"]["execution_state"] == "idle":
                return "".join(out)

    try:
        yield run
    finally:
        kc.stop_channels()
        km.shutdown_kernel(now=True)
