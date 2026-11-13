# Prints "Hello, world!" through the UART at 0x1000_0000 (a system with a bus: riscv.SocSystem),
# then ECALL. A store to the UART's data register waits while the UART is still sending.
    .text
    li    t0, 0x10000000      # the UART's data register
    la    t1, msg
1:  lbu   t2, 0(t1)
    beqz  t2, 2f              # the string ends with a 0 byte
    sb    t2, 0(t0)
    addi  t1, t1, 1
    j     1b
2:  ecall
    .data
msg: .string "Hello, world!\n"
