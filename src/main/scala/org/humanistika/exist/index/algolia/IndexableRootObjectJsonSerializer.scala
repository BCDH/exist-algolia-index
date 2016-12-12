package org.humanistika.exist.index.algolia

import java.io.StringWriter
import java.util.Properties
import javax.xml.bind.DatatypeConverter
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.sax.SAXResult
import javax.xml.transform.{OutputKeys, TransformerFactory}

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.{JsonSerializer, SerializerProvider}
import org.exist.storage.NodePath
import org.exist.util.serializer.{SAXSerializer, SerializerPool}
import org.humanistika.exist.index.algolia.LiteralTypeConfig.LiteralTypeConfig
import org.w3c.dom.{Element, Node}

import scalaz._
import Scalaz._

class IndexableRootObjectJsonSerializer extends JsonSerializer[IndexableRootObject] {
  override def serialize(value: IndexableRootObject, gen: JsonGenerator, serializers: SerializerProvider): Unit = {
    gen.writeStartObject()

    gen.writeStringField("objectID", value.collectionId + "/" + value.documentId + "/" + value.nodeId.getOrElse(0))
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

  private lazy val serializerPool = SerializerPool.getInstance
  private lazy val transformerFactory : TransformerFactory = new net.sf.saxon.TransformerFactoryImpl

  private def serializeAsText(node: Node) : String = {
    val properties = new Properties()
    properties.setProperty(OutputKeys.METHOD, "text")
    serialize(node, properties)
  }

  private def serializeAsJson(element: Element, typeMappings: Map[NodePath, (LiteralTypeConfig, Option[String])]) : String = {
    //TODO(AR) need to set the type/name mappings
    val properties = new Properties()
    properties.setProperty(OutputKeys.METHOD, "json")
    properties.setProperty(OutputKeys.INDENT, "no")
    serialize(element, properties)
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

  private def serialize(node: Node, properties: Properties) : String = {
    val handler = serializerPool.borrowObject(classOf[SAXSerializer]).asInstanceOf[SAXSerializer]
    try {
      val writer = new StringWriter()
      try {
        handler.setOutput(writer, properties)

        val transformer = transformerFactory.newTransformer()
        val result = new SAXResult(handler)
        transformer.transform(new DOMSource(node), result)
        return writer.toString
      } finally {
        writer.close
      }
    } finally {
      serializerPool.returnObject(handler)
    }
  }
}
