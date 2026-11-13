# R4: stores of bytes and half-words overwrote whole words. The RAM had a masked write port for the
# core's stores and an unmasked one for the program loader, and firtool dropped the masks of both.
    la    s0, d
    li    t0, 0x8081
    sh    t0, 2(s0)
    sb    t0, 1(s0)
    lw    a0, 0(s0)         # 0x80818144 when every byte is right
    lh    a1, 2(s0)
    lbu   a2, 1(s0)
    ecall
    .data
d:  .word 0x11223344
