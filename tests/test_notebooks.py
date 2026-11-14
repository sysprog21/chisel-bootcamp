#!/usr/bin/env python3
"""Static checks on the notebooks (no kernel needed).

Rules:
  links       relative links (Prev/Next and others) point to existing files
  images      local images exist
  alt-text    every image has non-empty alt text
  removed-api no removed Chisel 3 APIs, except deliberate mentions in markdown cells listed
              in tests/lint-allowlist.txt
  registered  every notebook is in `notebooks` of runtest.py, and every entry there exists
  outline     every notebook is in the README's outline table, with an estimated time
  troubleshooting  every notebook with a Setup section links docs/troubleshooting.md

Usage: tests/test_notebooks.py [--self-test]
  --self-test seeds one violation per rule into a copy of the notebooks and checks that each
              rule reports it, then checks the real notebooks.
"""

import glob
import importlib.util
import json
import os
import re
import shutil
import sys
import tempfile
import urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ALLOWLIST = os.path.join("tests", "lint-allowlist.txt")
# Removed Chisel 3 APIs, and legacy ones that new code should not use (chiseltest): name -> pattern.
# A mention counts even inside an import such as `import firrtl.{Transform, ...}`.
REMOVED_APIS = {
    "chiseltest": r"(?<!\w)chiseltest\b",
    "iotesters": r"(?<!\w)iotesters\b",
    "chisel3.stage": r"(?<!\w)chisel3\.stage\b",
    "firrtl.Transform": r"(?<!\w)firrtl\.(?:\{[^}]*\b)?Transform\b",
}

# An image may sit inside a link (`[![alt](img)](url)`), so images and links are matched separately.
MD_IMAGE = re.compile(r"!\[([^\]]*)\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
MD_LINK = re.compile(r"(?<!!)\[(?:[^\[\]]|!\[[^\]]*\]\([^)]*\))*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
HTML_IMG = re.compile(r"<img\b[^>]*>", re.IGNORECASE)
HTML_ATTR = re.compile(r"""\b(src|alt|href)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""", re.IGNORECASE)
HTML_LINK = re.compile(r"<a\b[^>]*>", re.IGNORECASE)
# Text that only looks like a link, e.g. `Vec[UInt](n)` in prose, is not a path.
PATH_LIKE = re.compile(r"^[\w./%-]+(?:[#?].*)?$")
CODE = re.compile(r"```.*?```|`[^`\n]*`", re.DOTALL)


def attrs(tag):
    return {m.group(1).lower(): next(g for g in m.groups()[1:] if g is not None) for m in HTML_ATTR.finditer(tag)}


def is_remote(target):
    """True for a URL with a scheme (http:, mailto:, ...) or an anchor in the same page."""
    return bool(urllib.parse.urlsplit(target).scheme) or target.startswith("#")


def local_path(root, target):
    path = urllib.parse.unquote(urllib.parse.urlsplit(target).path)
    return os.path.join(root, path.lstrip("/"))  # a leading / means the repository root


def cells(root):
    """Yields (notebook file name, cell index, cell type, source) for every cell."""
    for path in sorted(glob.glob(os.path.join(root, "*.ipynb"))):
        with open(path, encoding="utf-8") as f:
            nb = json.load(f)
        for index, cell in enumerate(nb["cells"]):
            yield os.path.basename(path), index, cell["cell_type"], "".join(cell["source"])


def markdown_cells(root):
    """Like `cells`, for markdown cells only and without code spans (whose links are not rendered)."""
    for name, index, kind, source in cells(root):
        if kind == "markdown":
            yield name, index, CODE.sub("", source)


def check_links(root):
    errors = []
    for name, index, source in markdown_cells(root):
        targets = [m.group(1) for m in MD_LINK.finditer(source)]
        targets += [attrs(t).get("href", "") for t in HTML_LINK.findall(source)]
        for target in targets:
            if PATH_LIKE.match(target) and not is_remote(target) and not os.path.exists(local_path(root, target)):
                errors.append(f"{name} cell {index}: link to missing file {target}")
    return errors


def images(root):
    """Yields (notebook, cell index, src, alt) for every image of a markdown cell."""
    for name, index, source in markdown_cells(root):
        for m in MD_IMAGE.finditer(source):
            yield name, index, m.group(2), m.group(1)
        for a in map(attrs, HTML_IMG.findall(source)):
            yield name, index, a.get("src", ""), a.get("alt")


def check_images(root):
    return [
        f"{name} cell {index}: " + (f"missing image {src}" if src else "image without src")
        for name, index, src, _ in images(root)
        if not src or (not is_remote(src) and not os.path.exists(local_path(root, src)))
    ]


def check_alt_text(root):
    return [
        f"{name} cell {index}: image {src} has no alt text"
        for name, index, src, alt in images(root)
        if not (alt or "").strip()
    ]


def read_allowlist(root):
    """:returns the set of (notebook, API) whose mention in markdown cells is deliberate."""
    allowed = set()
    path = os.path.join(root, ALLOWLIST)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for n, line in enumerate(f, 1):
                fields = line.split("#", 1)[0].split()
                if not fields:
                    continue
                if len(fields) != 2 or not fields[0].endswith(".ipynb") or fields[1] not in REMOVED_APIS:
                    raise SystemExit(
                        f"{ALLOWLIST}:{n}: expected '<notebook> <API>', API one of " + ", ".join(REMOVED_APIS)
                    )
                allowed.add(tuple(fields))
    return allowed


def check_removed_apis(root):
    allowed = read_allowlist(root)
    errors, used = [], set()
    for name, index, kind, source in cells(root):
        for api, pattern in REMOVED_APIS.items():
            if not re.search(pattern, source):
                continue
            if kind == "markdown" and (name, api) in allowed:  # code cells are never allowed
                used.add((name, api))
            else:
                errors.append(
                    f"{name} cell {index}: removed API {api} (if the mention in a markdown "
                    f"cell is deliberate, add '{name} {api}' to {ALLOWLIST})"
                )
    errors += [
        f"{ALLOWLIST}: stale entry '{n} {a}' (the notebook does not mention it)" for n, a in sorted(allowed - used)
    ]
    return errors


def notebook_names(root):
    return sorted(os.path.basename(p) for p in glob.glob(os.path.join(root, "*.ipynb")))


def check_registered(root):
    spec = importlib.util.spec_from_file_location("runtest", os.path.join(root, "runtest.py"))
    runtest = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runtest)
    registered = set(runtest.notebooks)
    present = set(notebook_names(root))
    return [f"{n}: not registered in `notebooks` of runtest.py" for n in sorted(present - registered)] + [
        f"runtest.py: {n} is registered but does not exist" for n in sorted(registered - present)
    ]


# a row of the outline table: | [name](name.ipynb) | topic | N min |
OUTLINE_ROW = re.compile(r"\| \[[^]]+\]\(([^)]+\.ipynb)\) \|.*\| \d+ min \|\s*$")


def check_outline(root):
    with open(os.path.join(root, "README.md"), encoding="utf-8") as f:
        readme = f.read()
    outline = readme.partition("\n## Outline\n")[2].partition("\n## ")[0]
    timed = {m.group(1) for m in map(OUTLINE_ROW.match, outline.splitlines()) if m}
    present = notebook_names(root)
    return [
        f"README.md: {n} is not in the outline table as '| [{n[:-6]}]({n}) | topic | N min |'"
        for n in present
        if n not in timed
    ] + [f"README.md: the outline table lists {n}, which does not exist" for n in sorted(timed - set(present))]


TROUBLESHOOTING = "](docs/troubleshooting.md)"


def check_troubleshooting(root):
    setup, linked = set(), set()
    for name, _, source in markdown_cells(root):
        if "## Setup" in source:
            setup.add(name)
        if TROUBLESHOOTING in source:
            linked.add(name)
    return [f"{n}: no link to docs/troubleshooting.md (put it under ## Setup)" for n in sorted(setup - linked)]


RULES = {
    "links": check_links,
    "images": check_images,
    "alt-text": check_alt_text,
    "removed-api": check_removed_apis,
    "registered": check_registered,
    "outline": check_outline,
    "troubleshooting": check_troubleshooting,
}


def run(root):
    return [e for rule in RULES.values() for e in rule(root)]


def _edit_cell(root, notebook, index, transform):
    path = os.path.join(root, notebook)
    with open(path, encoding="utf-8") as f:
        nb = json.load(f)
    cell = nb["cells"][index]
    cell["source"] = transform("".join(cell["source"]))
    with open(path, "w", encoding="utf-8") as f:
        json.dump(nb, f, indent=1)


def _append(text):
    """:returns a seed that appends `text` to a markdown cell (cell 1 holds the Prev/Next links)."""
    return lambda r: _edit_cell(r, "2.1_first_module.ipynb", 1, lambda s: s + "\n" + text + "\n")


# One synthetic violation per rule
SEEDS = {
    "links": _append("[Next](2.2_missing.ipynb)"),
    "images": _append("![A missing figure](images/missing.png)"),
    "alt-text": _append("![](images/chisel_1024.png)"),
    "removed-api": _append("`import chisel3.iotesters.{PeekPokeTester, Driver}`"),
    "registered": lambda r: shutil.copy(os.path.join(r, "0_demo.ipynb"), os.path.join(r, "9_unregistered.ipynb")),
    "outline": lambda r: shutil.copy(os.path.join(r, "0_demo.ipynb"), os.path.join(r, "9_not_outlined.ipynb")),
    "troubleshooting": lambda r: _edit_cell(
        r,
        "2.1_first_module.ipynb",
        2,
        lambda s: s.replace("[Troubleshooting](docs/troubleshooting.md)", "Troubleshooting"),
    ),
}


def self_test():
    ok = True
    for rule, seed in SEEDS.items():
        with tempfile.TemporaryDirectory() as tmp:
            for p in glob.glob(os.path.join(ROOT, "*.ipynb")) + [
                os.path.join(ROOT, f) for f in ("runtest.py", "README.md")
            ]:
                shutil.copy(p, tmp)
            for d in ("images", "docs"):  # targets of the notebooks' local links
                shutil.copytree(os.path.join(ROOT, d), os.path.join(tmp, d))
            os.makedirs(os.path.join(tmp, "tests"))
            shutil.copy(os.path.join(ROOT, ALLOWLIST), os.path.join(tmp, ALLOWLIST))
            if RULES[rule](tmp):  # the copy already fails: the real run below shows why
                print(f"self-test: rule {rule} fails before seeding", file=sys.stderr)
                ok = False
                continue
            seed(tmp)
            caught = RULES[rule](tmp)
            if not caught:
                print(f"self-test: rule {rule} did not catch its seeded violation", file=sys.stderr)
                ok = False
            else:
                print(f"self-test: rule {rule} caught: {caught[0]}")
    return ok


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        sys.exit(0)
    ok = self_test() if "--self-test" in sys.argv[1:] else True
    errors = run(ROOT)
    for e in errors:
        print(e, file=sys.stderr)
    if not errors:
        print(f"notebook lint: {len(RULES)} rules passed")
    sys.exit(0 if ok and not errors else 1)
