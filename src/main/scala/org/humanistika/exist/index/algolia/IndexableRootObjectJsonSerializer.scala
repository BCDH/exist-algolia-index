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

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.{JsonSerializer, SerializerProvider}
import IndexableRootObjectJsonSerializer._
import grizzled.slf4j.Logger
import JsonUtil._

import scalaz.{-\/, \/, \/-}
import scalaz.syntax.either._

object IndexableRootObjectJsonSerializer {
  val OBJECT_ID_FIELD_NAME = "objectID"
  val COLLECTION_PATH_FIELD_NAME = "collection"
  val DOCUMENT_ID_FIELD_NAME = "documentID"
}

class IndexableRootObjectJsonSerializer extends JsonSerializer[IndexableRootObject] {
  private val logger = Logger(classOf[IndexableRootObjectJsonSerializer])

  override def serialize(value: IndexableRootObject, gen: JsonGenerator, serializers: SerializerProvider) {
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
    val values: Seq[Throwable] \/ Seq[String] = attr.values.map( _ match {
      case IndexableValue(id, -\/(element)) =>
        element._2.right
      case IndexableValue(id, \/-(attribute)) =>
        attribute._2.right
    }).foldLeft((Seq.empty[Throwable], Seq.empty[String])) { case (accum, lr) =>
      lr match {
        case \/-(str) if accum._1.isEmpty =>
          (accum._1, accum._2 :+ str)
        case \/-(_) if accum._1.nonEmpty =>
          (accum._1, Seq.empty)
        case -\/(ts) =>
          (accum._1 ++ ts, Seq.empty)
      }
    } match {
      case (ts, strs) if ts.isEmpty =>
        strs.right
      case (ts, strs) if ts.nonEmpty =>
        ts.left
    }

    values match {
      case \/-(vs) if(vs.size > 1) =>
        gen.writeArrayFieldStart(attr.name)
        writeValueFields(gen, attr.literalType, vs)
        gen.writeEndArray()

      case \/-(vs) =>
        writeKeyValueField(gen, attr.literalType)(attr.name, vs.head)

      case -\/(ts) =>
        logger.error(s"Unable to serialize IndexableAttribute: ${attr.name}", ts)
    }
  }

  private def serializeObject(obj: IndexableObject, gen: JsonGenerator, serializers: SerializerProvider) {

    if(obj.values.size > 1) {
      gen.writeArrayFieldStart(obj.name)

      obj.values.map(_ match {
        case IndexableValue(id, -\/(element)) =>
          gen.writeStartObject()
          gen.writeStringField("nodeId", id)

          gen.writeRaw(element._2)

          gen.writeEndObject()

        case IndexableValue(_, \/-(attribute)) =>
          writeValueField(gen, LiteralTypeConfig.String, attribute._2)
      })

      gen.writeEndArray()

    } else {
      obj.values.headOption match {
        case Some(IndexableValue(id, -\/(element))) =>
          gen.writeObjectFieldStart(obj.name)
          gen.writeStringField("nodeId", id)

          gen.writeRaw(element._2)

          gen.writeEndObject()

        case Some(IndexableValue(_, \/-(attribute))) =>
          //a org.w3c.dom.Attr can never be converted to an object, so just serialize the value as a String field
          writeKeyValueField(gen, LiteralTypeConfig.String)(obj.name, attribute._2)

        case None =>
      }
    }
  }
}
