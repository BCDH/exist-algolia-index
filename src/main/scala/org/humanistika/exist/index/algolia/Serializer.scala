/*
 * Copyright (C) 2017  Belgrade Center for Digital Humanities
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.humanistika.exist.index.algolia

import java.io.StringWriter
import java.util.Properties
import javax.xml.transform.{OutputKeys, TransformerFactory}
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.sax.SAXResult
import com.fasterxml.jackson.core.{JsonFactory, JsonGenerator}
import org.exist.storage.NodePath
import org.exist.util.serializer.{SAXSerializer, SerializerPool}
import org.humanistika.exist.index.algolia.JsonUtil.writeValueField
import org.humanistika.exist.index.algolia.LiteralTypeConfig.LiteralTypeConfig
import org.w3c.dom.{Attr, Element, NamedNodeMap, Node, NodeList, Text}
import cats.syntax.either._

import scala.util.Using

object Serializer {

  private lazy val serializerPool = SerializerPool.getInstance
  private lazy val transformerFactory: TransformerFactory = new net.sf.saxon.TransformerFactoryImpl

  def serializeElementForAttribute(element: Element) : Either[Seq[Throwable], String] = serializeAsText(element)

  def serializeElementForObject(objName: String, serializerProperties: Map[String, String], typeMappings: Map[NodePath, (LiteralTypeConfig.LiteralTypeConfig, Option[Name])])(element: Element) : Either[Seq[Throwable], String] = {

    def serialize(element: Element) : Either[Seq[Throwable], JsonGenerator => Unit] = {
      serializeAsJson(element, serializerProperties, typeMappings)
        .map(jsonObjectAsObjectBody(_))
        .map(rawJson => { gen: JsonGenerator =>
          gen.writeRaw(',')
          gen.writeRaw(rawJson)
        })
    }

    def jsonObjectAsObjectBody(json: String): String = {
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

    /**
      * Determines if a node-list only contains text nodes
      */
    def hasOnlyTextChildren(childNodes: NodeList): Boolean = {
      val textNodes = for(i <- 0 until childNodes.getLength)
        yield childNodes.item(i).isInstanceOf[Text]
      !textNodes.contains(false)
    }

    def serializeAttributes(gen: JsonGenerator)(map: NamedNodeMap) = {
      for (i <- 0 until map.getLength) {
        val attr = map.item(i).asInstanceOf[Attr]
        gen.writeStringField(attr.getName, attr.getValue)
      }
    }

    def serializeTextNodes(gen: JsonGenerator)(textNodes: NodeList) {
      if(textNodes.getLength > 1) {
        gen.writeArrayFieldStart ("#text")
      } else {
        gen.writeFieldName("#text")
      }

      for(i <- 0 until textNodes.getLength) {
        val textNode = textNodes.item(i).asInstanceOf[Text]
        writeValueField(gen, LiteralTypeConfig.String, textNode.getNodeValue)
      }

      if(textNodes.getLength > 1) {
        gen.writeEndArray()
      }
    }

    def stripStartObjectEndObject(str: String): String = {
      val clean = str.trim
      if(!clean.isEmpty) {
        val first = if(clean.startsWith("{")) {
          clean.replaceFirst("""\{""", "")
        } else {
          clean
        }
        val last = if(first.endsWith("}")) {
          first.substring(0, first.length - 1)
        } else {
          first
        }
        last.trim
      } else {
        str
      }
    }

    Using.Manager { use =>
      val writer = use(new StringWriter())
      val gen = use(new JsonFactory().createGenerator(writer))

      val childNodes = element.getChildNodes
      if (hasOnlyTextChildren(childNodes)) {

        // needed so Jackson's JSON Generator won't complain
        gen.writeStartObject()

        gen.writeRaw(',')

        serializeAttributes(gen)(element.getAttributes)
        if (childNodes.getLength > 0) {
          serializeTextNodes(gen)(childNodes)
        }

        // needed so Jackson's JSON Generator won't complain
        gen.writeEndObject()
      } else {
        serialize(element).map { elementGenerator =>

          // needed so Jackson's JSON Generator won't complain
          gen.writeStartObject()

          elementGenerator(gen)

          // needed so Jackson's JSON Generator won't complain
          gen.writeEndObject()
        }
      }

      gen.flush()
      stripStartObjectEndObject(writer.toString)  // strip the extras we added for the JSON generator (so it won't complain)
    }
  }.toEither.leftMap(Seq(_))

  def serializeAsText(node: Node): Either[Seq[Throwable], String] = {
    val properties = new Properties()
    properties.setProperty(OutputKeys.METHOD, "text")

    if(node.isInstanceOf[Element]) {
      // serialize the children of the element (avoids also serializing the attribute values)

      val children = node.getChildNodes
      val text: Seq[Either[Seq[Throwable], String]] = for(i <- (0 until children.getLength))
        yield serialize(children.item(i), properties)

      /**
       * Fold over the `text` to convert from the type Seq[Either[Seq[Throwable], String]]
       * to the type Either[Seq[Throwable], String].
       *
       * This is done by iterating over each result item in `text`, if there
       * is an error, only errors are accumulated in a list and results are discarded.
       * If there are no errors, then the strings results are concatenated into a single string.
       */
      val initialValue = Either.right[Seq[Throwable], String]("")
      text.foldLeft(initialValue) { case (accum, item) =>
        accum match {
          case Left(accumErrors) =>
            item match {
              case Left(itemErrors) =>
                Left(accumErrors ++: itemErrors)
              case Right(_) =>
                accum
            }

          case Right(accumResult) =>
            item match {
              case Left(_) =>
                item
              case Right(itemResult) =>
                Right(accumResult + itemResult)
            }
        }
      }
    } else {
      serialize(node, properties)
    }
  }

  def serializeAsJson(element: Element, serializerProperties: Map[String, String], typeMappings: Map[NodePath, (LiteralTypeConfig, Option[String])]): Either[Seq[Throwable], String] = {
    //TODO(AR) need to set the type/name mappings
    val properties = new Properties()

    // default to indent = no
    properties.setProperty(OutputKeys.INDENT, "no")

    // set user specified serializer properties
    for((key, value) <- serializerProperties) {
      properties.setProperty(key, value)
    }

    // always the serialization type must be json,
    // which is why we set this after the user specified properties
    properties.setProperty(OutputKeys.METHOD, "json")

    serialize(element, properties)
  }

  def serialize(node: Node, properties: Properties): Either[Seq[Throwable], String] = {
    Using.Manager { use =>
      val serializer = use(serializerPool.borrowObject(classOf[SAXSerializer]).asInstanceOf[SAXSerializer])(serializer => serializerPool.returnObject(serializer))
      val writer = use(new StringWriter())

      serializer.setOutput(writer, properties)

      val transformer = transformerFactory.newTransformer()
      val result = new SAXResult(serializer)
      transformer.transform(new DOMSource(node), result)

      writer.toString
    }.toEither.leftMap(Seq(_))
  }
}
