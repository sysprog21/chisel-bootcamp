# factorial(5) = 120 in a0 (and in `result`), then ECALL. RV32I has no multiply instruction, so
# `mul` is a subroutine (shift and add); factorial is recursive, so this also checks calls, the
# stack and memory.
    .text
_start:
    li    sp, 0x80010000      # the top of the 64 KB RAM
    li    a0, 5
    call  factorial
    la    t0, result
    sw    a0, 0(t0)
    ecall
1:  j     1b

# a0 = factorial(a0)
factorial:
    li    t0, 1
    ble   a0, t0, 2f          # factorial(n) = 1 for n <= 1
    addi  sp, sp, -8
    sw    ra, 4(sp)
    sw    a0, 0(sp)
    addi  a0, a0, -1
    call  factorial           # a0 = factorial(n - 1)
    lw    a1, 0(sp)
    call  mul                 # a0 = factorial(n - 1) * n
    lw    ra, 4(sp)
    addi  sp, sp, 8
    ret
2:  li    a0, 1
    ret

# a0 = a0 * a1 (the low 32 bits), by shift and add; uses t0 and t1
mul:
    mv    t0, a0
    li    a0, 0
1:  beqz  a1, 2f
    andi  t1, a1, 1
    beqz  t1, 3f
    add   a0, a0, t0
3:  slli  t0, t0, 1
    srli  a1, a1, 1
    j     1b
2:  ret

    .data
result: .word 0
