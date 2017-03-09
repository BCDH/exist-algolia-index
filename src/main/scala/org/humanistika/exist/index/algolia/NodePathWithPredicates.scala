package org.humanistika.exist.index.algolia

import javax.xml.XMLConstants
import javax.xml.namespace.QName

import org.humanistika.exist.index.algolia.ComponentType.ComponentType

import scala.annotation.tailrec

case class TypedQName(name: QName, componentType: ComponentType)
case class Component(name: TypedQName, predicates: Seq[Predicate])
case class Predicate(left: TypedQName, comparisonOperator: ComparisonOperator, right: Seq[String])

object ComponentType extends Enumeration {
  type ComponentType = Value
  val ATTRIBUTE, ELEMENT, ANY = Value
}

sealed trait ComparisonOperator {
  val symbol: String
}
object AtomicEqualsComparison extends ComparisonOperator {
  override val symbol: String = "eq"
}
object AtomicNotEqualsComparison extends ComparisonOperator {
  override val symbol: String = "ne"
}
object SequenceEqualsComparison extends ComparisonOperator {
  override val symbol: String = "="
}

/**
  * Created by aretter on 09/03/2017.
  */
class NodePathWithPredicates(components: Seq[TypedQName]) {
  def size = components.size
  def get(idx: Int) = components(idx)
}

object NodePathWithPredicates {
  val SKIP = TypedQName(new QName("//"), ComponentType.ANY)
  val WILDCARD = TypedQName(new QName("*"), ComponentType.ANY)

  def apply(namespaces: Map[String, String], path: String): NodePathWithPredicates = {

    def toTypedQName(name: String): TypedQName = {
      def splitPrefixLocal(s: String): (Option[String], String) = {
        val idx = s.indexOf(':')
        if (idx > -1) {
          (Some(s.substring(0, idx)), s.substring(idx + 1))
        } else {
          (None, s)
        }
      }

      def extractPrefix(s: String): Option[String] = {
        val idx = s.indexOf(':')
        if (idx > -1) {
          Some(s.substring(0, idx))
        } else {
          None
        }
      }

      val (componentName, componentType) = if (name(0) == '@') {
        (name.substring(1), ComponentType.ATTRIBUTE)
      } else {
        (name, ComponentType.ELEMENT)
      }

      val (prefix, localName) = splitPrefixLocal(componentName)
      val namespaceUri: String = prefix.flatMap(namespaces.get(_)).getOrElse(XMLConstants.NULL_NS_URI)

      TypedQName(new QName(namespaceUri, localName, prefix.getOrElse(XMLConstants.DEFAULT_NS_PREFIX)), componentType)
    }

    @tailrec
    def parse(pos: Int, tokenStart: Int, accum: Seq[TypedQName]): Seq[TypedQName] = {
      if (pos == path.length) {
        if (pos - tokenStart != 0) {
          return toTypedQName(path.substring(tokenStart, pos)) +: accum
        } else {
          return accum
        }
      }

      path(pos) match {
        case '*' =>
          parse(pos + 1, pos + 1, WILDCARD +: accum)

        case '/' =>
          val next: Option[TypedQName] =
            if (tokenStart != 0 && pos - tokenStart != 0) {
              Some(toTypedQName(path.substring(tokenStart, pos)))
            } else {
              None
            }

          val skip: Option[TypedQName] =
            if (pos + 1 < path.length && path.charAt(pos + 1) == '/') {
              Some(SKIP)
            } else {
              None
            }

          parse(pos + 1, pos + 1, skip.toSeq ++ next.toSeq ++ accum)

        case _ =>
          parse(pos + 1, tokenStart, accum)
      }
    }

    val components: Seq[TypedQName] = parse(0, 0, Seq.empty).reverse
    new NodePathWithPredicates(components)
  }
}
