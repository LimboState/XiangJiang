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
  "vectorResultSlots": ${p.nVRes},
  "vectorRegisters": 32,
  "vectorLanes": ${p.vectorLanes},
  "vectorRegisterBits": ${p.vectorBits},
  "matrixArrays": 3,
  "matrixTileRows": ${p.matrixDim},
  "matrixTileCols": ${p.matrixDim},
  "matrixUnits": ["bf16", "int8", "int8"],
  "completionDataBits": ${p.vectorBits},
  "isaProfile": "XJ-NPU-1.0",
  "controlInterfaceVersion": 2,
  "coreDmaImplemented": true,
  "dmaMaxOutstandingBursts": 1,
  "dmaBufferBytes": 256,
  "externalAxiDataBits": 32,
  "physicalMatrixPeArrayImplemented": true,
  "matrixProcessingElements": 192,
  "vectorExecutionUnits": ["ialu", "imul", "bfpu", "bfdiv", "perm-red"],
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
