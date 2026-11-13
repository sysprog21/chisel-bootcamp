package riscv

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec

/** A seeded bug: forwards the older result (MEM/WB) before the newer one (EX/MEM). */
class OldestFirst extends Forward {
  def writes(r: Valid[Result]) = r.valid && r.bits.rd =/= 0.U && r.bits.rd === io.rs
  io.value := MuxCase(
    io.regValue,
    Seq(writes(io.memWb) -> io.memWb.bits.value, writes(io.exMem) -> io.exMem.bits.value)
  )
}

class DebugSpec extends AnyFlatSpec with ChiselSim {

  "Retire" should "read as a line of a trace" in {
    val r = Retire(0x80000008L, Asm("addi a0, a0, 10").head, 0x8000000cL, rd = 10, rdValue = 15)
    assert(r.toString == "80000008  00a50513  addi a0, a0, 10          a0 <- 0x0000000f")
    val halt = Retire(0x80000010L, 0x73, 0x80000010L, halt = Some(Halt.Ecall))
    assert(halt.toString == "80000010  00000073  ecall                    halt: Ecall")
  }

  "Vcd" should "read signals by path and sample them at each rising clock edge" in {
    val vcd = new Vcd(
      """$timescale 1ns $end
        |$scope module TOP $end
        |$var wire 1 ! clock $end
        |$scope module dut $end
        |$var wire 4 " count [3:0] $end
        |$upscope $end
        |$upscope $end
        |$enddefinitions $end
        |#0
        |$dumpvars
        |0!
        |bx "
        |$end
        |#1
        |1!
        |b0 "
        |#2
        |0!
        |#3
        |1!
        |b101 "
        |""".stripMargin
    )
    assert(vcd.signals == Seq("TOP.clock", "TOP.dut.count"))
    assert(vcd.risingEdges("clock") == Seq(1L, 3L))
    val count = vcd("dut.count")
    assert(count(0) == -1 && count(2) == 0 && count(3) == 5 && vcd("TOP.dut.count")(3) == 5)
  }

  "Co-simulation" should "find a forwarding unit that forwards the older result first" in {
    simulate(new CoreSystem(new ForwardingCore(forwardUnit = new OldestFirst))) { c =>
      val bug = CoSim.compare(c, Asm("li a0, 1\nli a0, 2\nadd a1, a0, a0\necall"))
      assert(bug.exists(_.contains("a1 <- 0x00000004")), bug) // the model's a1; the core computes 1 + 1
      assert(CoSim.compare(c, Asm(TestPrograms.source("programs/src", "fib"))).isEmpty, "fib does not show it")
    }
  }

  "Formal.prove" should "prove the forwarding unit, and give a counterexample for the seeded bug" in {
    assume(Formal.available, "yosys is not on the PATH")
    assert(Formal.prove(new ForwardCheck(new Forward)).isEmpty)
    val cex                 = Formal.prove(new ForwardCheck(new OldestFirst)).get
    def value(name: String) = cex.linesIterator.find(_.startsWith(s"\\io_$name ")).get.split("\\s+")(1).toLong
    assert(value("ok") == 0 && value("exMem_valid") == 1 && value("memWb_valid") == 1, cex)
    assert(value("exMem_bits_rd") == value("rs") && value("memWb_bits_rd") == value("rs") && value("rs") != 0, cex)
  }
}
