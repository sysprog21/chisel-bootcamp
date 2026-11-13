# Sums a table of 64 words twice, then ECALL with the sum in a0 (2 * 2016 = 4032): a loop of loads.
    .text
    li    a0, 0
    li    t2, 2               # passes
1:  la    t0, table
    li    t1, 64              # words
2:  lw    t3, 0(t0)
    add   a0, a0, t3
    addi  t0, t0, 4
    addi  t1, t1, -1
    bnez  t1, 2b
    addi  t2, t2, -1
    bnez  t2, 1b
    ecall
    .data
table:
    .word  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15
    .word 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31
    .word 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47
    .word 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63
