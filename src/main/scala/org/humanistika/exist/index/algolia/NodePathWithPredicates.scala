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

import javax.xml.XMLConstants
import javax.xml.namespace.QName
import org.exist.storage.NodePath
import org.humanistika.exist.index.algolia.NodePathWithPredicates._
import org.humanistika.exist.index.algolia.NodePathWithPredicates.ComponentType.ComponentType

import org.parboiled2._

import cats.syntax.either._

class NodePathWithPredicates(components: Seq[Component]) {
  def size = components.size

  def get(idx: Int) = components(idx)

  def foldLeft[B](z: B)(op: (B, Component) => B): B = components.foldLeft(z)(op)

  def asNodePath: NodePath = {
    val np = new NodePath
    for(component <- components) {
      val qnType = component.name.componentType match {
        case ComponentType.ATTRIBUTE =>
          org.exist.storage.ElementValue.ATTRIBUTE
        case _ =>
          org.exist.storage.ElementValue.ELEMENT
      }
      np.addComponent(new org.exist.dom.QName(component.name.name.getLocalPart, component.name.name.getNamespaceURI, component.name.name.getPrefix, qnType))
    }
    np
  }
}

object NodePathWithPredicates {
  type Prefix = String
  type NamespaceURI = String

  val SKIP = TypedQName(new QName("//"), ComponentType.ANY)
  val WILDCARD = TypedQName(new QName("*"), ComponentType.ANY)

  case class Component(name: TypedQName, predicates: Seq[Predicate] = Seq.empty)
  case class TypedQName(name: QName, componentType: ComponentType)
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

  def apply(components: Seq[Component]) = new NodePathWithPredicates(components)

  @throws[IllegalArgumentException]
  def parse(namespaces: Map[String, String], path: String): NodePathWithPredicates = {
    val parser = NodePathWithPredicatesParser(namespaces, path)
    parser.path.run().toEither match {
      case Right(result) =>
        result
      case Left(parseError : ParseError)  =>
          throw new IllegalArgumentException(parser.formatError(parseError))
      case Left(throwable) =>
        throw new IllegalArgumentException(throwable)
    }
  }
}


object NodePathWithPredicatesParser {
    def apply(namespaces: Map[Prefix, NamespaceURI], input: ParserInput): NodePathWithPredicatesParser = {
      new NodePathWithPredicatesParser(namespaces, input)
    }
}

class NodePathWithPredicatesParser(val namespaces: Map[Prefix, NamespaceURI], val input: ParserInput) extends Parser {

  def path = rule {
    absoluteOrRelativePathComponent ~ zeroOrMore(absolutePathComponent) ~ EOI ~> ((maybeLeadingSep: Option[Option[Component]], firstPathComponent: Component, furtherPathComponents: Seq[Seq[Component]]) => {
      val components = maybeLeadingSep.flatten.toSeq ++ Seq(firstPathComponent) ++ furtherPathComponents.flatten
      NodePathWithPredicates(components)
    })
  }

  private def absoluteOrRelativePathComponent = rule {
    optional(pathComponentSeparator) ~ pathComponent
  }

  private def absolutePathComponent = rule {
    pathComponentSeparator ~ pathComponent ~> ((separator, component) => (separator.toSeq :+ component))
  }

  private def pathComponentSeparator = rule {
    descendantOrSelfSeparator | childSeparator
  }

  private def descendantOrSelfSeparator = rule {
      str("//") ~ push(Option(Component(NodePathWithPredicates.SKIP, Seq.empty)))
  }

  private def childSeparator = rule {
    ch('/') ~ push(Option.empty[Component])
  }

  private def pathComponent = rule {
    wildcardComponent | attributeComponent | elementComponent
  }

  private def wildcardComponent = rule {
    wildcard ~ zeroOrMore(predicate) ~> (predicates => Component(NodePathWithPredicates.WILDCARD, predicates))
  }

  private def wildcard = rule {
    ch('*')
  }

  private def attributeComponent = rule {
    attributeName ~> (attribute => Component(attribute, Seq.empty))
  }

  private def attributeName = rule {
    ch('@') ~ qname ~> (qn => TypedQName(qn, ComponentType.ATTRIBUTE))
  }

  private def elementComponent = rule {
    elementName ~ zeroOrMore(predicate) ~> Component
  }

  private def elementName = rule {
    qname ~> (qn => TypedQName(qn, ComponentType.ELEMENT))
  }

  private def qname = rule {
    optional(capture(qnamePrefix) ~ ":") ~ capture(qnameLocalPart) ~> ((maybePrefix, localPart) => maybePrefix match {
      case Some(prefix) =>
        val ns = namespaces.get(prefix).getOrElse(XMLConstants.NULL_NS_URI)
        new QName(ns, localPart, prefix)
      case None =>
        new QName(localPart)
    })
  }

  private def qnamePrefix = rule {
    oneOrMore(CharPredicate.AlphaNum ++ '-')
  }

  private def qnameLocalPart = rule {
    oneOrMore(CharPredicate.AlphaNum ++ '-')
  }

  private def predicate = rule {
    '[' ~ predicateLeftExpression ~ WS ~ predicateOperator ~ WS ~ predicateRightExpression ~ ']' ~> ((left, op, right) => Predicate(left, op, Seq(right)))
  }

  private def predicateLeftExpression = rule {
    attributeName
  }

  private def predicateOperator = rule {
    atomicEqualsOperator | atomicNotEqualsOperator | sequenceEqualsOperator
  }

  private def atomicEqualsOperator = rule {
    str("eq") ~ push(AtomicEqualsComparison)
  }

  private def atomicNotEqualsOperator = rule {
    str("ne") ~ push(AtomicNotEqualsComparison)
  }

  private def sequenceEqualsOperator = rule {
    ch('=') ~ push(SequenceEqualsComparison)
  }

  private def predicateRightExpression = rule {
    predicateRightLiteral
  }

  private def predicateRightLiteral = rule {
    ch('\'') ~ capture(zeroOrMore(noneOf("'\""))) ~ ch('\'')
  }

  def WS = rule {
    quiet(zeroOrMore(anyOf(" \t \n")))
  }
}
