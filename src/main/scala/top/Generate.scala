package top

import xiangjiang.XJParams

object Generate extends App {
  val target = java.nio.file.Paths.get(args.headOption.getOrElse("generated"))
  java.nio.file.Files.createDirectories(target)
  val p = XJParams()
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
    new XiangJiangNPU(p),
    args = Array("--target-dir", target.toString),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  val configuration = s"""{
  "itcmBytes": ${p.itcmBytes},
  "dtcmBytes": ${p.dtcmBytes},
  "dtcmBankBytes": ${p.bankBytes},
  "robEntries": ${p.nRob},
  "issueEntriesPerUnit": ${p.nIssue},
  "scalarResultSlots": ${p.nXRes},
  "memoryQueueEntries": ${p.nMemq},
  "generationBits": ${p.generationBits},
  "sequenceBits": ${p.seqBits},
  "axiAddressBits": 32,
  "axiDataBits": 32,
  "axiIdBits": ${p.axiIdBits}
}
"""
  java.nio.file.Files.writeString(target.resolve("xiangjiang-config.json"), configuration)
  println(s"Generated XiangJiangNPU RTL and configuration in $target")
}
