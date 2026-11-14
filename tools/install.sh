#!/bin/bash
# Install what the bootcamp needs outside Docker, on Ubuntu 24.04 or newer: a C++ compiler, a JDK,
# Graphviz and Python from apt (with sudo); Verilator from apt if it is 5.026 or newer, or else
# built from source; sbt, a Python environment with Jupyter and Yosys, and the Almond Scala kernel
# in the user's home. Run it from a clone of the repository; it can be run again. Install.md
# describes the same steps by hand.
#
# Usage: tools/install.sh
# Then:  source ~/.local/share/chisel-bootcamp/venv/bin/activate && jupyter lab
#        (the environment also holds the sbt and the Verilator that the script installed)
set -euo pipefail

# The versions of the Docker image (tools/check-versions.py checks that they agree).
SCALA_VERSION=2.13.18
ALMOND_VERSION=0.14.5
COURSIER_VERSION=2.1.24
SBT_VERSION=1.13.1
SBT_SHA256=4a20e36c1642b6d5be34ef641d2ed6c8c0d464e6145283730d5566716e4083be
YOWASP_YOSYS_VERSION=0.71.0.0.post1285
YOWASP_RUNTIME_VERSION=1.96
WASMTIME_VERSION=47.0.1
# Verilator before 5.026 aborts now and then with "Internal Error: attempted to destroy locked
# Thread Pool" when ChiselSim compiles a design (verilator#5040); Ubuntu 24.04 has 5.020.
VERILATOR_MIN=5.026
VERILATOR_VERSION=5.038
VERILATOR_SHA256=f8c03105224fa034095ba6c8a06443f61f6f59e1d72f76b718f89060e905a0d4

root=$(cd "$(dirname "$0")/.." && pwd)
prefix=$HOME/.local/share/chisel-bootcamp
venv=$prefix/venv
mkdir -p "$prefix"
download() { curl -fsSL --retry 3 -o "$1" "$2"; }

sudo=""
[ "$(id -u)" -eq 0 ] || sudo=sudo
apt_install() { $sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends "$@"; }
$sudo apt-get update -qq
apt_install openjdk-17-jdk-headless verilator g++ make graphviz python3-venv curl ca-certificates git

# Jupyter and Yosys, in a Python environment of their own; the tools below are linked into its bin/
python3 -m venv "$venv"
"$venv/bin/pip" install -q --upgrade pip
"$venv/bin/pip" install -q jupyter "yowasp-yosys==$YOWASP_YOSYS_VERSION" \
    "yowasp-runtime==$YOWASP_RUNTIME_VERSION" "wasmtime==$WASMTIME_VERSION"

# Verilator: the system's if it is new enough, or else a release built into the user's home (in a
# directory per version, so that changing VERILATOR_VERSION builds the new one)
system_verilator=$(/usr/bin/verilator --version | awk '{print $2}')
if ! dpkg --compare-versions "$system_verilator" ge "$VERILATOR_MIN"; then
    built=$prefix/verilator-$VERILATOR_VERSION
    if [ ! -x "$built/bin/verilator" ]; then
        echo "Verilator $system_verilator is older than $VERILATOR_MIN: building $VERILATOR_VERSION (a few minutes)"
        apt_install autoconf flex bison help2man perl python3 libfl-dev zlib1g-dev
        src=$prefix/verilator-src
        log=$prefix/verilator-build.log
        rm -rf "$src" "$built" && mkdir -p "$src"
        download "$prefix/verilator.tgz" "https://github.com/verilator/verilator/archive/refs/tags/v${VERILATOR_VERSION}.tar.gz"
        echo "$VERILATOR_SHA256  $prefix/verilator.tgz" | sha256sum -c -
        tar -xzf "$prefix/verilator.tgz" -C "$src" --strip-components=1
        rm "$prefix/verilator.tgz"
        (cd "$src" && autoconf && ./configure --prefix="$built" && make -j"$(nproc)" && make install) >"$log" 2>&1 || {
            tail -n 50 "$log" >&2
            echo "tools/install.sh: building Verilator failed; the whole log is $log" >&2
            exit 1
        }
        rm -rf "$src" "$log"
    fi
    ln -sf "$built/bin/verilator" "$venv/bin/verilator"
else
    rm -f "$venv/bin/verilator" # one that an earlier run built, which would hide the system's
fi

# sbt, for the riscv/ project of Module 5 (in a directory per version, as Verilator)
sbt=$prefix/sbt-$SBT_VERSION
if [ ! -x "$sbt/bin/sbt" ]; then
    download "$prefix/sbt.tgz" "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz"
    echo "$SBT_SHA256  $prefix/sbt.tgz" | sha256sum -c -
    mkdir -p "$sbt" && tar -xzf "$prefix/sbt.tgz" -C "$sbt" --strip-components=1
    rm "$prefix/sbt.tgz"
fi
ln -sf "$sbt/bin/sbt" "$venv/bin/sbt"

# the Almond kernel, for Scala 2.13 (Chisel 7 needs it)
coursier=$prefix/coursier-$COURSIER_VERSION
if [ ! -x "$coursier" ]; then
    download "$coursier" "https://github.com/coursier/coursier/releases/download/v${COURSIER_VERSION}/coursier"
    chmod +x "$coursier"
fi
"$coursier" bootstrap -r jitpack "sh.almond:scala-kernel_${SCALA_VERSION}:${ALMOND_VERSION}" \
    --default=true -o "$prefix/almond" --force
# in the venv, which its jupyter always searches: the JVM takes its home from the user database,
# not from $HOME, so a per-user install can land where jupyter does not look (as in CI containers)
"$prefix/almond" --install --force --jupyter-path "$venv/share/jupyter/kernels"

# the bootcamp's notebook customization, unless there is another one already
custom=$HOME/.jupyter/custom/custom.js
mkdir -p "$(dirname "$custom")"
[ -e "$custom" ] || cp "$root/source/custom.js" "$custom"
cmp -s "$root/source/custom.js" "$custom" || echo "Left $custom as it is: append $root/source/custom.js to it by hand"

PATH="$venv/bin:$PATH"
echo "Installed: $(verilator --version), sbt $(sbt --script-version), $(dot -V 2>&1)"
echo "Start the bootcamp with: source $venv/bin/activate && cd $root && jupyter lab"
