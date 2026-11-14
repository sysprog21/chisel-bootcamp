package riscv

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** Checks ImmGen against the software decoder (Rv32i.decode) and RegFile against a Scala model. */
class ImmGenRegFileSpec extends AnyFlatSpec with ChiselSim {
  private def unsigned(x: Int) = BigInt(Integer.toUnsignedLong(x))

  "ImmGen" should "extract the immediate of every format like Rv32i.decode" in {
    // (not slli/srli/srai: their immediate field also holds funct7; the ALU uses only bits 4:0)
    val formats = Seq(
      ImmFormat.I -> Seq("ADDI", "LW", "JALR"),
      ImmFormat.S -> Seq("SW", "SB"),
      ImmFormat.B -> Seq("BEQ", "BGEU"),
      ImmFormat.U -> Seq("LUI", "AUIPC"),
      ImmFormat.J -> Seq("JAL")
    )
    simulate(new ImmGen) { c =>
      val rnd = new scala.util.Random(1)
      for ((format, names) <- formats; name <- names; _ <- 0 until 200) {
        val word     = Instructions.random(name, rnd)
        val expected = Rv32i.decode(word).get.imm
        c.io.inst.poke(word.U)
        c.io.format.poke(format)
        c.io.imm.expect(unsigned(expected).U, f"$name 0x$word%08x")
      }
    }
  }

  "RegFile" should "read two registers, write one at the clock edge, and keep x0 at 0" in {
    simulate(new RegFile) { c =>
      val rnd   = new scala.util.Random(2)
      val model = Array.fill(32)(BigInt(0))
      c.io.wen.poke(true.B) // first give every register a known value
      for (r <- 0 until 32) {
        model(r) = if (r == 0) 0 else BigInt(32, rnd)
        c.io.rd.poke(r.U)
        c.io.wdata.poke(if (r == 0) 12345.U else model(r).U) // the write to x0 is ignored
        c.clock.step()
      }
      for (cycle <- 0 until 1000) {
        val (rs1, rs2, rd, dbg) = (rnd.nextInt(32), rnd.nextInt(32), rnd.nextInt(32), rnd.nextInt(32))
        val (wen, wdata)        = (rnd.nextBoolean(), BigInt(32, rnd))
        c.io.rs1.poke(rs1.U)
        c.io.rs2.poke(rs2.U)
        c.io.dbgAddr.poke(dbg.U)
        c.io.wen.poke(wen.B)
        c.io.rd.poke(rd.U)
        c.io.wdata.poke(wdata.U)
        c.io.rs1Data.expect(model(rs1).U, s"cycle $cycle: x$rs1")
        c.io.rs2Data.expect(model(rs2).U, s"cycle $cycle: x$rs2")
        c.io.dbgData.expect(model(dbg).U, s"cycle $cycle: x$dbg (debug port)")
        c.clock.step()
        if (wen && rd != 0) model(rd) = wdata
      }
    }
  }
}
