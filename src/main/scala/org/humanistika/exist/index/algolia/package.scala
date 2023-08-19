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

package org.humanistika.exist.index

import java.nio.file.Path
import javax.xml.namespace.QName

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import grizzled.slf4j.Logger
import org.exist.dom.persistent.{AttrImpl, ElementImpl}
import org.exist.storage.NodePath
import org.humanistika.exist.index.algolia.IndexableRootObjectJsonSerializer.OBJECT_ID_FIELD_NAME
import org.w3c.dom.{Attr, Element}

package object algolia {

  type Name = String
  type IndexName = Name

  type ElementOrAttributeImpl = Either[ElementImpl, AttrImpl]
  type ElementOrAttribute = Either[Element, Attr]
  type ElementKV = (QName, String)  // qname and serialized XML as JSON
  type AttributeKV = (QName, String)  // substitute for org.exist.dom.memtree.AttrImpl as we have has trouble converting from org.exist.dom.persistent.AttrImpl
  type ElementOrAttributeKV = Either[ElementKV, AttributeKV]

  type IndexableAttributeOrObject = Either[IndexableAttribute, IndexableObject]

  object LiteralTypeConfig extends Enumeration {
    type LiteralTypeConfig = Value
    val String, Integer, Float, Boolean, Date, DateTime = Value
  }

  type UserSpecifiedDocumentId = String
  type UserSpecifiedNodeId = String

  type CollectionPath = String
  type CollectionId = Int
  type DocumentId = Int
  type objectID = String

  @JsonSerialize(using=classOf[IndexableRootObjectJsonSerializer]) case class IndexableRootObject(collectionPath: CollectionPath, collectionId: CollectionId, documentId: DocumentId, userSpecifiedDocumentId: Option[UserSpecifiedDocumentId], nodeId: Option[String], userSpecifiedNodeId: Option[UserSpecifiedNodeId], children: Seq[Either[IndexableAttribute, IndexableObject]])
  case class IndexableAttribute(name: Name, values: IndexableValues, literalType: LiteralTypeConfig.LiteralTypeConfig)
  case class IndexableObject(name: Name, values: IndexableValues)

  type IndexableValues = Seq[IndexableValue]
  case class IndexableValue(id: String, value: ElementOrAttributeKV)

  @JsonSerialize(using=classOf[LocalIndexableRootObjectJsonSerializer]) case class LocalIndexableRootObject(path: Path)

  def readObjectId(file: Path, mapper: ObjectMapper): Option[objectID] = {
    val prevJsonNode = mapper.readTree(file.toFile)
    Option(prevJsonNode.get(OBJECT_ID_FIELD_NAME))
      .flatMap(node => Option(node.asText()))
  }

  implicit class LoggerUtils(val logger: Logger) {
    def error(msg: => String, ts: Seq[Throwable]) = {
      logger.error(msg)
      for(t <- ts) {
        logger.error(t.getMessage, t)
      }
    }
  }
}
