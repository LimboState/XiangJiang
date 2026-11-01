package top

import org.scalatest.funsuite.AnyFunSuite

class VivadoResourcesSpec extends AnyFunSuite {
  private val sources = Map(
    "itcm.sv" -> "module memory_65536x32(\n);\n  reg [31:0] Memory[0:65535];\nendmodule\n",
    "dtcm.sv" -> "module memory_4096x512(\n);\n  reg [511:0] Memory[0:4095];\nendmodule\n",
    "bf16.sv" -> """module SystolicPE(
);
  reg [31:0] accumulator;
  always @(posedge clock) begin
    automatic logic [15:0] product_product = a * b;
    accumulator <= accumulator + product;
  end
endmodule
""",
    "int8.sv" -> """module SystolicPE_64(
);
  reg [31:0] accumulator;
  always @(posedge clock) begin
    automatic logic [15:0] _product_T_2 = a * b;
    accumulator <= accumulator + product;
  end
endmodule
""",
    "vector.sv" -> "module VectorUnit(); wire [31:0] p = a * b; endmodule\n")

  test("default policy annotates only SRAM declarations and targeted PE arithmetic") {
    val result = Elaborate.annotateResources(sources, Elaborate.ResourcePolicy())
    assert(result.sources("itcm.sv").contains("(* ram_style = \"block\" *) reg"))
    assert(result.sources("dtcm.sv").contains("(* ram_style = \"block\" *) reg"))
    assert(result.sources("bf16.sv").contains("(* use_dsp = \"yes\" *) automatic logic [15:0] product_product"))
    assert(!result.sources("bf16.sv").contains("*) reg [31:0] accumulator"))
    assert(result.sources("int8.sv").contains("(* use_dsp = \"yes\" *) reg [31:0] accumulator"))
    assert(result.sources("vector.sv") == sources("vector.sv"))
    // Removing the attributes yields byte-identical arithmetic and registers.
    result.sources.foreach { case (name, text) =>
      assert(text.replaceAll("\\(\\* (ram_style|use_dsp) = \"[^\"]+\" \\*\\) ", "") == sources(name))
    }
    assert(result.report.contains("bf16.sv"))
  }

  test("LUTRAM and LUT-arithmetic comparison policy is explicit and reproducible") {
    val result = Elaborate.annotateResources(sources, Elaborate.ResourcePolicy("distributed", "no"))
    assert(result.sources("itcm.sv").contains("ram_style = \"block\""))
    assert(result.sources("dtcm.sv").contains("ram_style = \"distributed\""))
    assert(result.sources("bf16.sv").contains("use_dsp = \"no\""))
    assert(result.sources("int8.sv").contains("use_dsp = \"no\""))
    intercept[IllegalArgumentException] { Elaborate.ResourcePolicy("auto", "yes") }
    intercept[IllegalArgumentException] { Elaborate.ResourcePolicy("block", "auto") }
  }

  test("missing, changed, duplicate and already annotated structures fail loudly") {
    val policy = Elaborate.ResourcePolicy()
    intercept[IllegalArgumentException] { Elaborate.annotateResources(sources - "itcm.sv", policy) }
    intercept[IllegalArgumentException] {
      Elaborate.annotateResources(sources.updated("itcm.sv", sources("itcm.sv").replace("65535", "131071")), policy)
    }
    intercept[IllegalArgumentException] {
      Elaborate.annotateResources(sources.updated("copy.sv", sources("bf16.sv")), policy)
    }
    intercept[IllegalArgumentException] {
      Elaborate.annotateResources(sources.updated("bf16.sv", sources("bf16.sv").replace("a * b", "a + b")), policy)
    }
    intercept[IllegalArgumentException] {
      Elaborate.annotateResources(Elaborate.annotateResources(sources, policy).sources, policy)
    }
  }
}
