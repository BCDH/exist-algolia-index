package org.humanistika.exist.index.algolia

import java.io.StringWriter
import java.nio.file.{Files, Path}

import com.algolia.search.inputs.batch.BatchAddObjectOperation
import com.fasterxml.jackson.databind.ObjectMapper
import org.specs2.Specification
import resource.managed
import java.nio.charset.StandardCharsets.UTF_8

import scala.util.{Failure, Success}

class LocalIndexableRootObjectJsonSerializerSpec extends Specification { def is = s2"""
  This is a specification to check the JSON Serialization of IndexableRootObject

    The basic JSON serialized result must
      must be round-tripable $e1
      work in a Batch Operation $e2
  """

  def e1 = {
    val json = """{"objectID":"86/754/3.5.2.2.6","collection":"/db/t1","dict":"MZ.RGJS.аба2","lemma":"аба"}"""
    val file = createTempJsonFile(json)
    serializeJson(LocalIndexableRootObject(file)) mustEqual """{"objectID":"86/754/3.5.2.2.6","collection":"/db/t1","dict":"MZ.RGJS.аба2","lemma":"аба"}"""
  }

  def e2 = {
    val json = """{"objectID":"86/754/3.5.2.2.6","collection":"/db/t1","dict":"MZ.RGJS.аба2","lemma":"аба"}"""
    val file = createTempJsonFile(json)
    val batch = new BatchAddObjectOperation[LocalIndexableRootObject](LocalIndexableRootObject(file))
    serializeJson(batch) mustEqual s"""{"body":$json,"action":"addObject"}"""
  }

  private def createTempJsonFile(json: String) : Path = {
    val p = Files.createTempFile("test", "json")
    managed(Files.newBufferedWriter(p, UTF_8)).map { writer =>
      writer.write(json)
    }.tried match {
      case Success(_) =>
        p
      case Failure(t) =>
        throw t
    }
  }

  private def serializeJson[T](obj: T): String = {
    managed(new StringWriter).map { writer =>
      val mapper = new ObjectMapper
      mapper.writeValue(writer, obj)
      writer.toString
    }.tried match {
      case Success(s) =>
        s
      case Failure(t) =>
        throw t
    }
  }
}
