# Contributing

## Notebooks are committed without outputs

Commit notebooks without cell outputs, execution counts or editor metadata (such as
`collapsed` or `scrolled`), so that diffs show only real changes. A pre-commit hook strips
them for you:

```bash
pip install pre-commit
pre-commit install            # once per clone: the hook then runs on every `git commit`
pre-commit run --all-files    # check (and fix) every file now
```

CI runs the same hook and fails if it would change a notebook.

## Checks you can run without a kernel

```bash
python3 tools/check-versions.py              # pinned versions agree across the repository
python3 tests/test_notebooks.py --self-test  # links, images, alt text, removed APIs, runtest.py
```

`tools/check-versions.py` compares every pinned version with the `Dockerfile` (the files and
patterns are in its `CHECKS` table) and checks that `FIRTOOL_VERSION` is the firtool version
Chisel was published against. To upgrade a version, change it everywhere the script reports.

`tests/test_notebooks.py` rejects mentions of removed Chisel 3 APIs (its `REMOVED_APIS`). A
deliberate mention, such as a migration note, is
allowed by adding `<notebook> <API>` to `tests/lint-allowlist.txt`; code cells are never allowed.

## Running the notebooks

`runtest.py` runs notebooks in the Scala kernel and compares the errors they raise with the
expected ones (exercises with `???` and cells that fail on purpose raise errors by design).
In the Docker image, without network access:

```bash
docker build -t chisel-bootcamp .
docker run --rm --network none chisel-bootcamp python3 runtest.py 2.1_first_module.ipynb
docker run --rm --network none chisel-bootcamp python3 runtest.py   # every notebook
```

Locally (see `Install.md`), run `python3 runtest.py <notebook>` from the repository root.
`BOOTCAMP_CELL_TIMEOUT` sets the per-cell timeout in seconds (default 600). `runtest.py` treats
every deprecation warning as an unexpected error. The warning names the deprecated call, except
for code in `source/load-ivy.sc`, which only gets a count: compile the suspect definitions in a
cell of their own to see the details.

## Adding a notebook

1. Add an entry to the `notebooks` dict in `runtest.py` with the errors it is expected to
   raise, in order. CI runs every registered notebook on pushes to `dev` and nightly; a pull
   request runs a smoke subset plus the notebooks it changes, or every notebook when it changes
   shared files such as `source/` or the Dockerfile (see `tools/ci-notebooks.py`).
2. Link it from the previous notebook's Prev/Next links (and link back to the previous one
   from the new notebook), and add it to the outline in `README.md`.
3. Give every image non-empty alt text.
4. Run the checks above and `runtest.py` on the new notebook.
