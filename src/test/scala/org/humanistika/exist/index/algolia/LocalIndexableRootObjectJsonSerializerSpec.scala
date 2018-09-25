package org.humanistika.exist.index.algolia

import java.io.StringWriter
import java.nio.file.{Files, Path}

import com.algolia.search.inputs.batch.BatchAddObjectOperation
import com.fasterxml.jackson.databind.ObjectMapper
import org.specs2.Specification
import java.nio.charset.StandardCharsets.UTF_8

import cats.effect.{IO, Resource}

import scalaz._
import Scalaz._

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
    Resource.fromAutoCloseable(IO {Files.newBufferedWriter(p, UTF_8)}).use { writer =>
      IO {
        writer.write(json)
      }
    }.redeem(_.left, _.right).unsafeRunSync() match {
      case \/-(_) =>
        p
      case -\/(t) =>
        throw t
    }
  }

  private def serializeJson[T](obj: T): String = {
    Resource.fromAutoCloseable(IO {new StringWriter}).use { writer =>
      IO {
        val mapper = new ObjectMapper
        mapper.writeValue(writer, obj)
        writer.toString
      }
    }.redeem(_.left, _.right).unsafeRunSync() match {
      case \/-(s) =>
        s
      case -\/(t) =>
        throw t
    }
  }
}
