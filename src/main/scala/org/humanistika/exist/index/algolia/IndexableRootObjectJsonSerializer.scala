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

/**
  * Created by aretter on 04/12/2016.
  */
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
    val value = attr.value match {
      case -\/(element) =>
        serializeAsText(element)
      case \/-(attribute) =>
        attribute.getValue
    }

    attr.literalType match {
      case LiteralTypeConfig.Integer =>
        gen.writeNumberField(attr.name, value.toInt)
      case LiteralTypeConfig.Float =>
        gen.writeNumberField(attr.name, value.toFloat)
      case LiteralTypeConfig.Boolean =>
        gen.writeBooleanField(attr.name, value.toBoolean)
      case LiteralTypeConfig.String =>
        gen.writeStringField(attr.name, value)
      case LiteralTypeConfig.Date =>
        gen.writeNumberField(attr.name, DatatypeConverter.parseDate(value).getTimeInMillis)
      case LiteralTypeConfig.DateTime =>
        gen.writeNumberField(attr.name, DatatypeConverter.parseDateTime(value).getTimeInMillis)
    }
  }

  private def serializeObject(obj: IndexableObject, gen: JsonGenerator, serializers: SerializerProvider) {
    obj.value match {
      case -\/(element) =>
        gen.writeObjectFieldStart(obj.name)
        gen.writeStringField("nodeId", obj.nodeId)

        val json = serializeAsJson(element, obj.typeMappings)
        val jsonBody = jsonObjectAsObjectBody(json)
        gen.writeRaw(',')
        gen.writeRaw(jsonBody)

        gen.writeEndObject()

      case attr @ \/-(attribute) =>
        //a org.w3c.dom.Attr can never be converted to an object, so just serialize the value as a String field
        serializeAttribute(IndexableAttribute(obj.name, obj.nodeId, attr, LiteralTypeConfig.String), gen, serializers)
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
