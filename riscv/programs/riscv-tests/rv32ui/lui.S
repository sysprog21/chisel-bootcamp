# riscv-tests 3df703cd52d5, isa/rv32ui/lui.S, expanded by tools/import-riscv-tests.py
# against riscv/programs/riscv-tests/riscv_test.h. Do not edit; see ../LICENSE.
.text; .globl _start; _start:
  test_2: li gp, 2; lui x1, 0x00000; li x7, ((0x0000000000000000) & ((1 << (32 - 1) << 1) - 1)); bne x1, x7, fail;;
  test_3: li gp, 3; lui x1, 0xfffff;sra x1,x1,1; li x7, ((0xfffffffffffff800) & ((1 << (32 - 1) << 1) - 1)); bne x1, x7, fail;;
  test_4: li gp, 4; lui x1, 0x7ffff;sra x1,x1,20; li x7, ((0x00000000000007ff) & ((1 << (32 - 1) << 1) - 1)); bne x1, x7, fail;;
  test_5: li gp, 5; lui x1, 0x80000;sra x1,x1,20; li x7, ((0xfffffffffffff800) & ((1 << (32 - 1) << 1) - 1)); bne x1, x7, fail;;
  test_6: li gp, 6; lui x0, 0x80000; li x7, ((0) & ((1 << (32 - 1) << 1) - 1)); bne x0, x7, fail;;
  bne x0, gp, pass; fail: fence; 1: beqz gp, 1b; sll gp, gp, 1; or gp, gp, 1; mv a0, gp; ecall; pass: fence; li gp, 1; li a0, 0; ecall
  .data
.data; .align 4
