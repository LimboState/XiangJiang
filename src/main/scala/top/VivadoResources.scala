package top

/** Checked, export-only attributes. Never modify the simulation RTL or timing. */
private[top] object VivadoResources {
  case class Policy(dtcmRam: String = "distributed", peDsp: String = "yes") {
    require(Set("block", "distributed")(dtcmRam), "dtcmRam must be block or distributed")
    require(Set("yes", "no")(peDsp), "peDsp must be yes or no")
  }
  case class Result(sources: Map[String, String], report: String)

  def apply(sources: Map[String, String], policy: Policy): Result = {
    val changes = scala.collection.mutable.ArrayBuffer.empty[String]
    var output = sources
    def select(module: String): String = {
      val header = ("(?m)^module " + module + "\\(").r
      val matches = sources.keys.filter(k => header.findFirstIn(sources(k)).nonEmpty).toVector
      require(matches.size == 1, s"Vivado resource policy: expected one $module module, found ${matches.size}")
      matches.head
    }
    def annotate(file: String, declaration: String, attribute: String): Unit = {
      val text = output(file)
      require(!text.contains(s"(* $attribute *) $declaration"), s"Already annotated: $file/$declaration")
      val count = text.sliding(declaration.length).count(_ == declaration)
      require(count == 1, s"Vivado resource policy: expected one '$declaration' in $file, found $count; review firtool output")
      output = output.updated(file, text.replace(declaration, s"(* $attribute *) $declaration"))
      changes += s"$file: $attribute -> $declaration"
    }
    // These signatures deliberately pin the current default SRAM geometry.
    // Changing capacity/compiler output must update and test the policy too.
    val itcm = select("memory_131072x32")
    val dtcm = select("memory_64x512")
    annotate(itcm, "reg [31:0] Memory[0:131071];", "ram_style = \"block\"")
    annotate(dtcm, "reg [511:0] Memory[0:63];", s"ram_style = \"${policy.dtcmRam}\"")

    val bf16 = select("SystolicPE")
    val int8 = select("SystolicPE_256")
    def multiplier(file: String, name: String): Unit = {
      val declaration = s"automatic logic [15:0] $name ="
      val statement = (java.util.regex.Pattern.quote(declaration) + "[^;]*;").r
        .findAllIn(output(file)).toVector
      require(statement.size == 1 && statement.head.count(_ == '*') == 1,
        s"Vivado resource policy: expected one 16-bit multiply assignment for $name in $file")
      require(" \\* ".r.findAllIn(output(file)).size == 1,
        s"Unexpected multiplier count in $file; review PE mapping")
      annotate(file, declaration, s"use_dsp = \"${policy.peDsp}\"")
    }
    multiplier(bf16, "product_product") // 8x8 significand only; NOT FP32 adder
    multiplier(int8, "_product_T_2")
    annotate(int8, "reg [31:0] accumulator;", s"use_dsp = \"${policy.peDsp}\"")

    val report = s"""ZCU102 Vivado resource inference policy
ITCM: 512 KiB -> block RAM (planning estimate: 128 RAMB36)
DTCM: 4 x 4 KiB -> ${policy.dtcmRam} RAM
PE significand/integer multipliers: use_dsp=${policy.peDsp}
INT8 accumulator: use_dsp=${policy.peDsp} (permit MAC inference)
BF16 FP32 accumulator, vector arithmetic, tile buffers and control: automatic
No capacity, arithmetic, latency, clock constraint or ISA changes.
Attributes guide synthesis; actual BRAM/DSP/LUT usage and timing require Vivado reports.
Targets are checked against the emitted source manifest; unknown structures fail export.

Applied declarations (shared module definitions cover every instance):
${changes.mkString("\n")}
"""
    Result(output, report)
  }
}
