#!/bin/bash
# Check the riscv/ reference solutions: copy the project, put riscv/solutions/ over its src/ and
# run every test. The student scaffold in riscv/src/ only has to compile, which is checked too.
#
# Usage: tools/check-solutions.sh            (from a clone)
#        SOLUTIONS=/path tools/check-solutions.sh   (e.g. in the Docker image, which has no solutions)
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
solutions=${SOLUTIONS:-$root/riscv/solutions}
[ -d "$solutions" ] || {
    echo "no solutions at $solutions" >&2
    exit 1
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# only what the build reads, not build output or editor files
mkdir -p "$work/project"
cp "$root/riscv/build.sbt" "$work/"
cp "$root/riscv/project/build.properties" "$work/project/"
for f in "$root"/riscv/project/*.sbt "$root"/riscv/project/*.scala; do # plugins and build code, if any
    [ -e "$f" ] && cp "$f" "$work/project/"
done
cp -R "$root/riscv/src" "$root/riscv/programs" "$work/" # the tests read programs/

# A solution replaces the scaffold file at the same path; one without a scaffold is a mistake
# (checked first: it needs no compile)
(cd "$solutions" && find . -type f | sed 's|^\./||') | while IFS= read -r f; do
    [ -f "$work/src/$f" ] || {
        echo "solution $f has no scaffold at riscv/src/$f" >&2
        exit 1
    }
done

echo "== The scaffold compiles"
(cd "$work" && sbt -batch Test/compile)

echo "== The solutions pass every test"
cp -R "$solutions/." "$work/src/"
(cd "$work" && sbt -batch test)
