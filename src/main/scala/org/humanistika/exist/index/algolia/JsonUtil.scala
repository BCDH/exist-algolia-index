package org.humanistika.exist.index.algolia

import jakarta.xml.bind.DatatypeConverter

import com.fasterxml.jackson.core.JsonGenerator
import org.humanistika.exist.index.algolia.LiteralTypeConfig.LiteralTypeConfig

object JsonUtil {

  def writeKeyValueField(gen: JsonGenerator, literalType: LiteralTypeConfig): (String, String) => Unit = {
    (key, value) =>
      literalType match {
        case LiteralTypeConfig.Integer =>
          gen.writeNumberField(key, value.toInt)
        case LiteralTypeConfig.Float =>
          gen.writeNumberField(key, value.toFloat)
        case LiteralTypeConfig.Boolean =>
          gen.writeBooleanField(key, value.toBoolean)
        case LiteralTypeConfig.String =>
          gen.writeStringField(key, value)
        case LiteralTypeConfig.Date =>
          gen.writeNumberField(key, DatatypeConverter.parseDate(value).getTimeInMillis)
        case LiteralTypeConfig.DateTime =>
          gen.writeNumberField(key, DatatypeConverter.parseDateTime(value).getTimeInMillis)
      }
  }

  def writeValueFields(gen: JsonGenerator, literalType: LiteralTypeConfig, values: Seq[String]) {
    for(value <- values) {
      writeValueField(gen, literalType, value)
    }
  }

  def writeValueField(gen: JsonGenerator, literalType: LiteralTypeConfig, value: String) {
    literalType match {
      case LiteralTypeConfig.Integer =>
        gen.writeNumber(value.toInt)
      case LiteralTypeConfig.Float =>
        gen.writeNumber(value.toFloat)
      case LiteralTypeConfig.Boolean =>
        gen.writeBoolean(value.toBoolean)
      case LiteralTypeConfig.String =>
        gen.writeString(value)
      case LiteralTypeConfig.Date =>
        gen.writeNumber(DatatypeConverter.parseDate(value).getTimeInMillis)
      case LiteralTypeConfig.DateTime =>
        gen.writeNumber(DatatypeConverter.parseDateTime(value).getTimeInMillis)
    }
  }
}
