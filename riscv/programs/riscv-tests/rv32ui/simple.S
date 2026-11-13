# riscv-tests 3df703cd52d5, isa/rv32ui/simple.S, expanded by tools/import-riscv-tests.py
# against riscv/programs/riscv-tests/riscv_test.h. Do not edit; see ../LICENSE.
.text; .globl _start; _start:
fence; li gp, 1; li a0, 0; ecall
  .data
.data; .align 4
