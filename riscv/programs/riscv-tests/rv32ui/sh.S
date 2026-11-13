# riscv-tests 3df703cd52d5, isa/rv32ui/sh.S, expanded by tools/import-riscv-tests.py
# against riscv/programs/riscv-tests/riscv_test.h. Do not edit; see ../LICENSE.
.text; .globl _start; _start:
  test_2: li gp, 2; la x2, tdat; li x1, 0x00000000000000aa; la x15, 7f; sh x1, 0(x2); lh x14, 0(x2); j 8f; 7: mv x14, x1; 8:; li x7, ((0x00000000000000aa) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_3: li gp, 3; la x2, tdat; li x1, 0xffffffffffffaa00; la x15, 7f; sh x1, 2(x2); lh x14, 2(x2); j 8f; 7: mv x14, x1; 8:; li x7, ((0xffffffffffffaa00) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_4: li gp, 4; la x2, tdat; li x1, 0xffffffffbeef0aa0; la x15, 7f; sh x1, 4(x2); lw x14, 4(x2); j 8f; 7: mv x14, x1; 8:; li x7, ((0xffffffffbeef0aa0) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_5: li gp, 5; la x2, tdat; li x1, 0xffffffffffffa00a; la x15, 7f; sh x1, 6(x2); lh x14, 6(x2); j 8f; 7: mv x14, x1; 8:; li x7, ((0xffffffffffffa00a) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_6: li gp, 6; la x2, tdat8; li x1, 0x00000000000000aa; la x15, 7f; sh x1, -6(x2); lh x14, -6(x2); j 8f; 7: mv x14, x1; 8:; li x7, ((0x00000000000000aa) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_7: li gp, 7; la x2, tdat8; li x1, 0xffffffffffffaa00; la x15, 7f; sh x1, -4(x2); lh x14, -4(x2); j 8f; 7: mv x14, x1; 8:; li x7, ((0xffffffffffffaa00) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_8: li gp, 8; la x2, tdat8; li x1, 0x0000000000000aa0; la x15, 7f; sh x1, -2(x2); lh x14, -2(x2); j 8f; 7: mv x14, x1; 8:; li x7, ((0x0000000000000aa0) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_9: li gp, 9; la x2, tdat8; li x1, 0xffffffffffffa00a; la x15, 7f; sh x1, 0(x2); lh x14, 0(x2); j 8f; 7: mv x14, x1; 8:; li x7, ((0xffffffffffffa00a) & ((1 << (32 - 1) << 1) - 1)); bne x14, x7, fail;;
  test_10: li gp, 10; la x1, tdat9; li x2, 0x12345678; addi x4, x1, -32; sh x2, 32(x4); lh x5, 0(x1);; li x7, ((0x5678) & ((1 << (32 - 1) << 1) - 1)); bne x5, x7, fail;
  test_11: li gp, 11; la x1, tdat9; li x2, 0x00003098; addi x1, x1, -5; sh x2, 7(x1); la x4, tdat10; lh x5, 0(x4);; li x7, ((0x3098) & ((1 << (32 - 1) << 1) - 1)); bne x5, x7, fail;
  test_12: li gp, 12; li x4, 0; 1: li x13, 0xffffffffffffccdd; la x12, tdat; sh x13, 0(x12); lh x14, 0(x12); li x7, 0xffffffffffffccdd; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_13: li gp, 13; li x4, 0; 1: li x13, 0xffffffffffffbccd; la x12, tdat; nop; sh x13, 2(x12); lh x14, 2(x12); li x7, 0xffffffffffffbccd; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_14: li gp, 14; li x4, 0; 1: li x13, 0xffffffffffffbbcc; la x12, tdat; nop; nop; sh x13, 4(x12); lh x14, 4(x12); li x7, 0xffffffffffffbbcc; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_15: li gp, 15; li x4, 0; 1: li x13, 0xffffffffffffabbc; nop; la x12, tdat; sh x13, 6(x12); lh x14, 6(x12); li x7, 0xffffffffffffabbc; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_16: li gp, 16; li x4, 0; 1: li x13, 0xffffffffffffaabb; nop; la x12, tdat; nop; sh x13, 8(x12); lh x14, 8(x12); li x7, 0xffffffffffffaabb; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_17: li gp, 17; li x4, 0; 1: li x13, 0xffffffffffffdaab; nop; nop; la x12, tdat; sh x13, 10(x12); lh x14, 10(x12); li x7, 0xffffffffffffdaab; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_18: li gp, 18; li x4, 0; 1: la x2, tdat; li x1, 0x2233; sh x1, 0(x2); lh x14, 0(x2); li x7, 0x2233; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_19: li gp, 19; li x4, 0; 1: la x2, tdat; li x1, 0x1223; nop; sh x1, 2(x2); lh x14, 2(x2); li x7, 0x1223; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_20: li gp, 20; li x4, 0; 1: la x2, tdat; li x1, 0x1122; nop; nop; sh x1, 4(x2); lh x14, 4(x2); li x7, 0x1122; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_21: li gp, 21; li x4, 0; 1: la x2, tdat; nop; li x1, 0x0112; sh x1, 6(x2); lh x14, 6(x2); li x7, 0x0112; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_22: li gp, 22; li x4, 0; 1: la x2, tdat; nop; li x1, 0x0011; nop; sh x1, 8(x2); lh x14, 8(x2); li x7, 0x0011; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  test_23: li gp, 23; li x4, 0; 1: la x2, tdat; nop; nop; li x1, 0x3001; sh x1, 10(x2); lh x14, 10(x2); li x7, 0x3001; bne x14, x7, fail; addi x4, x4, 1; li x5, 2; bne x4, x5, 1b;
  li a0, 0xbeef
  la a1, tdat
  sh a0, 6(a1)
  bne x0, gp, pass; fail: fence; 1: beqz gp, 1b; sll gp, gp, 1; or gp, gp, 1; mv a0, gp; ecall; pass: fence; li gp, 1; li a0, 0; ecall
  .data
.data; .align 4
tdat:
tdat1: .half 0xbeef
tdat2: .half 0xbeef
tdat3: .half 0xbeef
tdat4: .half 0xbeef
tdat5: .half 0xbeef
tdat6: .half 0xbeef
tdat7: .half 0xbeef
tdat8: .half 0xbeef
tdat9: .half 0xbeef
tdat10: .half 0xbeef
