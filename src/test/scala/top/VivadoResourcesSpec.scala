package top

import org.scalatest.funsuite.AnyFunSuite

class VivadoResourcesSpec extends AnyFunSuite {
  private val sources = Map(
    "itcm.sv" -> "module memory_131072x32(\n);\n  reg [31:0] Memory[0:131071];\nendmodule\n",
    "dtcm.sv" -> "module memory_64x512(\n);\n  reg [511:0] Memory[0:63];\nendmodule\n",
    "bf16.sv" -> """module SystolicPE(
);
  reg [31:0] accumulator;
  always @(posedge clock) begin
    automatic logic [15:0] product_product = a * b;
    accumulator <= accumulator + product;
  end
endmodule
""",
    "int8.sv" -> """module SystolicPE_256(
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
    val result = VivadoResources(sources, VivadoResources.Policy())
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
    val result = VivadoResources(sources, VivadoResources.Policy("distributed", "no"))
    assert(result.sources("itcm.sv").contains("ram_style = \"block\""))
    assert(result.sources("dtcm.sv").contains("ram_style = \"distributed\""))
    assert(result.sources("bf16.sv").contains("use_dsp = \"no\""))
    assert(result.sources("int8.sv").contains("use_dsp = \"no\""))
    intercept[IllegalArgumentException] { VivadoResources.Policy("auto", "yes") }
    intercept[IllegalArgumentException] { VivadoResources.Policy("block", "auto") }
  }

  test("missing, changed, duplicate and already annotated structures fail loudly") {
    val policy = VivadoResources.Policy()
    intercept[IllegalArgumentException] { VivadoResources(sources - "itcm.sv", policy) }
    intercept[IllegalArgumentException] {
      VivadoResources(sources.updated("itcm.sv", sources("itcm.sv").replace("131071", "65535")), policy)
    }
    intercept[IllegalArgumentException] {
      VivadoResources(sources.updated("copy.sv", sources("bf16.sv")), policy)
    }
    intercept[IllegalArgumentException] {
      VivadoResources(sources.updated("bf16.sv", sources("bf16.sv").replace("a * b", "a + b")), policy)
    }
    intercept[IllegalArgumentException] {
      VivadoResources(VivadoResources(sources, policy).sources, policy)
    }
  }
}
