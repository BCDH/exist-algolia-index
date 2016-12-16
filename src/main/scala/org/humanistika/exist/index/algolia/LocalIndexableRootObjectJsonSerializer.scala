package org.humanistika.exist.index.algolia

import java.nio.file.Files

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.{JsonSerializer, SerializerProvider}

import java.nio.charset.StandardCharsets.UTF_8

class LocalIndexableRootObjectJsonSerializer extends JsonSerializer[LocalIndexableRootObject] {
  override def serialize(value: LocalIndexableRootObject, gen: JsonGenerator, serializers: SerializerProvider) {
    val jsonStr = new String(Files.readAllBytes(value.path), UTF_8)
    gen.writeRawValue(jsonStr)
  }
}
