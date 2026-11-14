#!/bin/bash
# Build a bitstream of Ulx3sTop (the system of Module 5.8, running programs/src/<program>.S) for a
# ULX3S board: SystemVerilog (sbt), synthesis (Yosys), place and route (nextpnr-ecp5) and the
# bitstream (ecppack), all in fpga/build/. Each tool is taken as `tool` or, else, as YoWASP's
# `yowasp-tool` (pip install yowasp-yosys yowasp-nextpnr-ecp5). The bootcamp image has Yosys only.
#
# Usage: fpga/ulx3s.sh [program] [12k|25k|45k|85k]   (from riscv/; default: hello on a 12k board)
# Then:  openFPGALoader -b ulx3s fpga/build/Ulx3sTop.bit
set -euo pipefail

program=${1:-hello}
device=${2:-12k}
[[ $program =~ ^[A-Za-z0-9_-]+$ ]] || {
    echo "fpga/ulx3s.sh: the program is the name of a file of programs/src, without .S" >&2
    exit 1
}
[[ $device =~ ^(12k|25k|45k|85k)$ ]] || {
    echo "fpga/ulx3s.sh: the device is 12k, 25k, 45k or 85k" >&2
    exit 1
}
cd "$(dirname "$0")/.."

tool() { # the command for a tool, or its YoWASP build
    for cmd in "$1" "yowasp-$1"; do
        if command -v "$cmd" >/dev/null; then
            echo "$cmd"
            return
        fi
    done
    echo "fpga/ulx3s.sh: neither $1 nor yowasp-$1 is installed" >&2
    exit 1
}
yosys=$(tool yosys)
nextpnr=$(tool nextpnr-ecp5)
ecppack=$(tool ecppack)

rm -f fpga/build/Ulx3sTop.bit # a failed build must not leave an old bitstream to flash
sbt -batch "runMain riscv.Ulx3s $program"
"$yosys" -q -p "read_verilog -sv fpga/build/Ulx3sTop.sv; synth_ecp5 -top Ulx3sTop; delete t:\$scopeinfo; write_json fpga/build/Ulx3sTop.json"
"$nextpnr" "--$device" --package CABGA381 --json fpga/build/Ulx3sTop.json --lpf fpga/ulx3s.lpf \
    --textcfg fpga/build/Ulx3sTop.config
"$ecppack" --compress fpga/build/Ulx3sTop.config fpga/build/Ulx3sTop.bit
echo "fpga/build/Ulx3sTop.bit: program it with openFPGALoader -b ulx3s fpga/build/Ulx3sTop.bit"
