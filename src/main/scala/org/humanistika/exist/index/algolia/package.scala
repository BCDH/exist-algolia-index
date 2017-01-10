package org.humanistika.exist.index

import java.nio.file.Path

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import org.exist.dom.persistent.{AttrImpl, ElementImpl}
import org.exist.storage.NodePath
import org.w3c.dom.{Attr, Element}

import scalaz.\/

package object algolia {

  type Name = String
  type IndexName = Name

  type ElementOrAttributeImpl = ElementImpl \/ AttrImpl
  type ElementOrAttribute = Element \/ Attr

  type IndexableAttributeOrObject = IndexableAttribute \/ IndexableObject

  object LiteralTypeConfig extends Enumeration {
    type LiteralTypeConfig = Value
    val String, Integer, Float, Boolean, Date, DateTime = Value
  }

  type UserSpecifiedDocumentId = String
  type UserSpecifiedNodeId = String

  type CollectionPath = String
  type CollectionId = Int
  type DocumentId = Int

  @JsonSerialize(using=classOf[IndexableRootObjectJsonSerializer]) case class IndexableRootObject(collectionPath: CollectionPath, collectionId: CollectionId, documentId: DocumentId, userSpecifiedDocumentId: Option[UserSpecifiedDocumentId], nodeId: Option[String], userSpecifiedNodeId: Option[UserSpecifiedNodeId], children: Seq[IndexableAttribute \/ IndexableObject])
  case class IndexableAttribute(name: Name, values: IndexableValues, literalType: LiteralTypeConfig.LiteralTypeConfig)
  case class IndexableObject(name: Name, values: IndexableValues, typeMappings: Map[NodePath, (LiteralTypeConfig.LiteralTypeConfig, Option[Name])])

  type IndexableValues = Seq[IndexableValue]
  case class IndexableValue(id: String, value: ElementOrAttribute)

  @JsonSerialize(using=classOf[LocalIndexableRootObjectJsonSerializer]) case class LocalIndexableRootObject(path: Path)
}
