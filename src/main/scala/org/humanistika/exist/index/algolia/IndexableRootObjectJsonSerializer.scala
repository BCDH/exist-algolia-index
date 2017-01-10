package org.humanistika.exist.index.algolia

import javax.xml.bind.DatatypeConverter

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.{JsonSerializer, SerializerProvider}
import org.humanistika.exist.index.algolia.LiteralTypeConfig.LiteralTypeConfig
import IndexableRootObjectJsonSerializer._
import org.w3c.dom.Element

import Serializer._

import scalaz._
import Scalaz._

object IndexableRootObjectJsonSerializer {
  val OBJECT_ID_FIELD_NAME = "objectID"
  val COLLECTION_PATH_FIELD_NAME = "collection"
  val DOCUMENT_ID_FIELD_NAME = "documentID"
}

class IndexableRootObjectJsonSerializer extends JsonSerializer[IndexableRootObject] {
  override def serialize(value: IndexableRootObject, gen: JsonGenerator, serializers: SerializerProvider): Unit = {
    gen.writeStartObject()

    val objectId = value.userSpecifiedNodeId.getOrElse(s"${value.collectionId}/${value.documentId}/${value.nodeId.getOrElse(0)}")
    gen.writeStringField(OBJECT_ID_FIELD_NAME, objectId)

    gen.writeStringField(COLLECTION_PATH_FIELD_NAME, value.collectionPath)
    value.userSpecifiedDocumentId match {
      case Some(usd) =>
        gen.writeStringField(DOCUMENT_ID_FIELD_NAME, usd)
      case None =>
        gen.writeNumberField(DOCUMENT_ID_FIELD_NAME, value.documentId)
    }

//    gen.writeNumberField("collId", value.collectionId)
//    gen.writeNumberField("docId", value.documentId)
//    value.nodeId.map(gen.writeStringField("nodeId", _))

    serializeChildren(value.children, gen, serializers)

    gen.writeEndObject()
  }

  private def serializeChildren(children: Seq[IndexableAttribute \/ IndexableObject], gen: JsonGenerator, serializers: SerializerProvider) {
    for(child <- children) {
      child match {
        case -\/(attr)=>
          serializeAttribute(attr, gen, serializers)
        case \/-(obj)=>
          serializeObject(obj, gen, serializers)
      }
    }
  }

  private def serializeAttribute(attr: IndexableAttribute, gen: JsonGenerator, serializers: SerializerProvider) {
    val values: Seq[String] = attr.values.map( _ match {
      case IndexableValue(id, -\/(element)) =>
        serializeAsText(element)
      case IndexableValue(id, \/-(attribute)) =>
        attribute.getValue
    })

    if(values.size > 1) {
      gen.writeArrayFieldStart(attr.name)
      writeValueFields(gen, attr.literalType, values)
      gen.writeEndArray()
    } else {
      writeKeyValueField(gen, attr.literalType)(attr.name, values(0))
    }
  }

  private def writeKeyValueField(gen: JsonGenerator, literalType: LiteralTypeConfig): (String, String) => Unit = {
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

  private def writeValueFields(gen: JsonGenerator, literalType: LiteralTypeConfig, values: Seq[String]) {
    for(value <- values) {
      writeValueField(gen, literalType, value)
    }
  }

  private def writeValueField(gen: JsonGenerator, literalType: LiteralTypeConfig, value: String) {
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

  private def serializeObject(obj: IndexableObject, gen: JsonGenerator, serializers: SerializerProvider) {
    def serialize(element: Element) = {
      val json = serializeAsJson(element, obj.typeMappings)
      val jsonBody = jsonObjectAsObjectBody(json)
      gen.writeRaw(',')
      gen.writeRaw(jsonBody)
    }

    if(obj.values.size > 1) {
      gen.writeArrayFieldStart(obj.name)

      obj.values.map(_ match {
        case IndexableValue(id, -\/(element)) =>
          gen.writeStartObject()
          gen.writeStringField("nodeId", id)

          serialize(element)

          gen.writeEndObject()
        case IndexableValue(_, \/-(attribute)) =>
          writeValueField(gen, LiteralTypeConfig.String, attribute.getValue)
      })

      gen.writeEndArray()

    } else {
      obj.values.map(_ match {
        case IndexableValue(id, -\/(element)) =>
          gen.writeObjectFieldStart(obj.name)
          gen.writeStringField("nodeId", id)

          serialize(element)

          gen.writeEndObject()

        case IndexableValue(_, \/-(attribute)) =>
          //a org.w3c.dom.Attr can never be converted to an object, so just serialize the value as a String field
          writeKeyValueField(gen, LiteralTypeConfig.String)(obj.name, attribute.getValue)
      })
    }
  }

  private def jsonObjectAsObjectBody(json: String): String = {
    var tmp: String = json
    if(tmp.startsWith("{ ")) {
      tmp = tmp.substring(2)
    } else if(tmp.startsWith("{")) {
      tmp = tmp.substring(1)
    }

    if(tmp.endsWith(" }")) {
      tmp = tmp.substring(0, tmp.length - 2)
    } else if(tmp.endsWith("}")) {
      tmp = tmp.substring(0, tmp.length - 1)
    }

    //replace all whitespace that is not between quotes
    tmp = tmp.replaceAll("""\s+(?=([^"]*"[^"]*")*[^"]*$)""", "")    //TODO(AR) this is only needed until JSONWriter in exist adheres to indent=no

    tmp
  }
}
