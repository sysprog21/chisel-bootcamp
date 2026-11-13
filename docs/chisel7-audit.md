# Chisel 7 deprecation audit

Run on 2026-11-13 against Chisel 7.16.0, Scala 2.13.18 and Almond 0.14.5, in the
Docker image with `--network none`.

## Method

Every notebook was run in a fresh kernel with a copy of `source/load-ivy.sc` whose compiler
options add `-deprecation` and `-feature` to `-language:reflectiveCalls -Ytasty-reader`. Every
line of cell output containing `warn`, `deprecat` or `[W0` (Chisel's elaboration warnings) was
collected. Cells that contain `???` were marked as exercises so that their errors are not counted;
no exercise cell produced a warning.

`load-ivy.sc` itself is compiled before its own `configureCompiler` call takes effect, so its
warnings only show up as a summary ("1 deprecation ... re-run enabling -deprecation"). To find
the culprit, its definitions were compiled one per cell after the options were set.

With every warning fixed, `-Werror` adds nothing: the run reports no warnings at all.

## Findings

| Notebook | Cell | Warning | Fix |
| :--- | :--- | :--- | :--- |
| every notebook | load cell (`source/load-ivy.sc`, `visualize`) | `1 deprecation (since 0.4.1)`: `almond.api.helpers.Display.html` is deprecated | fix: `almond.display.Html(html).display()` |
| `1_intro_to_scala.ipynb` | 17 | `procedure syntax is deprecated: instead, add ': Unit =' to explicitly declare 'asciiTriangle''s return type` | fix: `def asciiTriangle(rows: Int): Unit = {` |
| `1_intro_to_scala.ipynb` | 21, 23, 25 | `method + in class Int is deprecated (since 2.13.0): Adding a number and a String is deprecated. Use the string interpolation` | fix: `print(s"$i ")` |

## Not deprecated

No compiler or Chisel message was reported for these, so they stay as they are: `Module(new ...)`,
`FlatIO`, `suggestName`, and `chisel3.util.experimental.*` (`decode` is the supported route for
table-driven decoders). `decoder(input, table)` tries the external `espresso` tool first and prints
a warning when it is missing, so notebooks should call `decoder.qmc` (the built-in minimizer) directly.

## Keeping it clean

The kernel names each deprecated call in a cell even without `-deprecation` (checked with a seeded
cell), and `runtest.py` reports each line of output that mentions a deprecation as an error, so CI
fails when a deprecated API comes back. The warnings of `load-ivy.sc` itself appear only as the
"re-run enabling -deprecation" summary line, which contains the same word and is caught too.
