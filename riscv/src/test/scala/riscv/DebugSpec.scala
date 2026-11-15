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

  def parse(svg: String) = javax.xml.parsers.DocumentBuilderFactory
    .newInstance()
    .newDocumentBuilder()
    .parse(new java.io.ByteArrayInputStream(svg.getBytes("UTF-8")))

  "Waves" should "draw a clock, bits and buses as well-formed SVG" in {
    val times = Seq(0L, 1L, 2L, 3L)
    val svg   = Waves.svg(
      times,
      times.map(_.toString),
      Seq(
        Waves.Bit("valid", t => if (t == 3) -1 else t % 2), // x in the last cycle
        Waves.Bus("value", t => if (t < 2) "" else s"x10 = $t <&>") // to escape
      ),
      mark = Set(2),
      note = "a note"
    )
    val doc   = parse(svg)
    val texts = doc.getElementsByTagName("text")
    val shown = (0 until texts.getLength).map(texts.item(_).getTextContent)
    assert(Seq("clock", "valid", "value", "a note", "x10 = 2 <&>", "x10 = 3 <&>").forall(shown.contains), shown)
    assert(doc.getElementsByTagName("polygon").getLength == 2) // a band per value
    assertThrows[IllegalArgumentException](Waves.svg(times, Seq("0"), Nil))
    assertThrows[IllegalArgumentException](Waves.svg(Nil, Nil, Nil))

    // one cycle, a value too long for its band, marks out of range, a decimal comma locale
    val locale = java.util.Locale.getDefault
    java.util.Locale.setDefault(java.util.Locale.GERMANY)
    val one =
      try Waves.svg(Seq(0L), Seq("0"), Seq(Waves.Bus("v", _ => "x" * 100)), mark = Set(-1, 5))
      finally java.util.Locale.setDefault(locale)
    val parsed = parse(one)
    val number = "-?[0-9]+(\\.[0-9]+)?"
    assert(parsed.getDocumentElement.getAttribute("width").matches(number), one)
    assert(parsed.getElementsByTagName("rect").getLength == 1, one) // the frame, no highlight
    val texts1 = parsed.getElementsByTagName("text")
    val band   = (0 until texts1.getLength).map(texts1.item(_).getTextContent).filter(_.startsWith("x"))
    assert(band.size == 1 && band.head.endsWith("…") && band.head.length < 100, band)
  }

  "Co-simulation" should "find a forwarding unit that forwards the older result first" in {
    simulate(new CoreSystem(new ForwardingCore(forwardUnit = new OldestFirst))) { c =>
      val bug = CoSim.compare(c, Asm("li a0, 1\nli a0, 2\nadd a1, a0, a0\necall"))
      assert(bug.exists(_.contains("a1 <- 0x00000004")), bug) // the model's a1; the core computes 1 + 1
      assert(CoSim.compare(c, Asm(Programs.source("programs/src", "fib"))).isEmpty, "fib does not show it")
    }
  }

  "Formal.prove" should "prove the forwarding unit, and give a counterexample for the seeded bug" in {
    assume(Yosys.available, "yosys is not on the PATH")
    assert(Formal.prove(new ForwardCheck(new Forward)).isEmpty)
    val cex                 = Formal.prove(new ForwardCheck(new OldestFirst)).get
    def value(name: String) = cex.linesIterator.find(_.startsWith(s"\\io_$name ")).get.split("\\s+")(1).toLong
    assert(value("ok") == 0 && value("exMem_valid") == 1 && value("memWb_valid") == 1, cex)
    assert(value("exMem_bits_rd") == value("rs") && value("memWb_bits_rd") == value("rs") && value("rs") != 0, cex)
  }
}
