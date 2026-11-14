# Regression programs

Programs that once exposed a difference between a core and the golden model (`riscv.IsaSim`).
`RegressSpec` runs each `.S` file here (today on the single-cycle core; later cores join it) and
compares what the core retires with the golden model, one test per program.

When `CoSim.compare` or a random program finds a bug, save the program here with a comment that
says what went wrong: `RandomProgram(seed)` returns the program's source text, starting with a
`# RandomProgram(seed = ...)` line. Reset clears neither the RAM nor the registers (nor does a fresh
simulation promise their values), so a program must not read a register or a memory word it has not
written (or that is not in its image).
