# Fibonacci(10) = 55 in a0, then ECALL. Uses only additions, branches and jumps.
    .section .text.init
    .globl _start
_start:
    li    t0, 10          # how many steps
    li    a0, 0           # fib(0)
    li    a1, 1           # fib(1)
loop:
    beqz  t0, done
    add   t1, a0, a1
    mv    a0, a1
    mv    a1, t1
    addi  t0, t0, -1
    j     loop
done:
    ecall
1:  j     1b
