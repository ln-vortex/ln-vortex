package com.lnvortex.core.gen

import org.bitcoins.testkitcore.Implicits.GeneratorOps
import play.api.libs.json.Json

import java.io.File
import java.nio.file.Files

object VortexMessageTestVectorGen extends App {

  val messages = 0.until(1000).map { _ =>
    Generators.vortexMessage.sampleSome
  }

  val json = Json.toJson(messages)

  val path = new File("core-test/src/test/resources/vortex_messages.json")

  println(s"Writing to ${path.getAbsolutePath}")

  Files.createDirectories(path.toPath.getParent)
  Files.write(path.toPath, Json.prettyPrint(json).getBytes)

  println("done")
}
