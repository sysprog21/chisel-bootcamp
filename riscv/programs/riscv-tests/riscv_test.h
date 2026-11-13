// A CSR-free test environment for riscv-tests (replaces env/p/riscv_test.h of the riscv-tests
// repository, which sets up traps with CSRs that the course's cores do not have). A test starts at
// _start, at the first word of .text; it ends with ECALL, with a0 = 0 if it passed, or a0 =
// (number of the failing test << 1) | 1 if it failed (gp holds the test number as it runs).
#define RVTEST_RV64U
#define RVTEST_RV32U
#define TESTNUM gp
#define RVTEST_CODE_BEGIN .text; .globl _start; _start:
#define RVTEST_CODE_END
#define RVTEST_PASS fence; li TESTNUM, 1; li a0, 0; ecall
#define RVTEST_FAIL fence; 1: beqz TESTNUM, 1b; sll TESTNUM, TESTNUM, 1; or TESTNUM, TESTNUM, 1; mv a0, TESTNUM; ecall
#define RVTEST_DATA_BEGIN .data; .align 4
#define RVTEST_DATA_END
