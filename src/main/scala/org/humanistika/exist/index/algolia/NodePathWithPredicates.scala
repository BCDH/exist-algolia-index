package org.humanistika.exist.index.algolia

import javax.xml.XMLConstants
import javax.xml.namespace.QName

import org.exist.storage.NodePath
import org.humanistika.exist.index.algolia.NodePathWithPredicates._
import org.humanistika.exist.index.algolia.NodePathWithPredicates.ComponentType.ComponentType

import scala.util.parsing.combinator._
import scalaz._
import Scalaz._
import scala.annotation.tailrec

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

  @throws[IllegalArgumentException]
  def apply(namespaces: Map[String, String], path: String): NodePathWithPredicates = {
    NodePathWithPredicatesParser.parsePath(namespaces, path) match {
      case \/-(result) =>
        result
      case -\/(errorMsg) =>
        throw new IllegalArgumentException(errorMsg)
    }
  }
}


object NodePathWithPredicatesParser extends RegexParsers {

  def parsePath(namespaces: Map[Prefix, NamespaceURI], nodePathString: String): String \/ NodePathWithPredicates = {
    parseAll(path(namespaces), nodePathString) match {
      case Success(result, _) =>
        result.right
      case NoSuccess(msg, _) =>
        msg.left
    }
  }

  private def path(namespaces: Map[Prefix, NamespaceURI]): Parser[NodePathWithPredicates] = opt(pathComponentSeparator) ~ pathComponent(namespaces) ~ rep(pathComponentSeparator ~ pathComponent(namespaces)) ^^ {
    case leadingSep ~ firstPathComponent ~ furtherPathComponents =>
      val components: Seq[Component] = leadingSep.flatten.toSeq ++ (firstPathComponent +: furtherPathComponents.flatMap { case pcs ~ pc => Seq(pcs.toSeq :+ pc)}.flatten)
      new NodePathWithPredicates(components)
  }

  private def pathComponentSeparator: Parser[Option[Component]] = descendantOrSelfSeparator | childSeparator

  private def descendantOrSelfSeparator = "//" ^^^ {
    Some(Component(NodePathWithPredicates.SKIP, Seq.empty))
  }

  private def childSeparator = "/" ^^^ {
    None
  }

  private def pathComponent(namespaces: Map[Prefix, NamespaceURI]): Parser[Component] = wildcardComponent(namespaces) | attributeComponent(namespaces) | elementComponent(namespaces)

  private def wildcardComponent(namespaces: Map[Prefix, NamespaceURI]) = wildcard ~> rep(predicate(namespaces)) ^^ {
    case predicates =>
      Component(NodePathWithPredicates.WILDCARD, predicates)
  }

  private def wildcard = "*"

  private def attributeComponent(namespaces: Map[Prefix, NamespaceURI]) = attributeName(namespaces) ^^ {
    case attribute =>
      Component(attribute, Seq.empty)
  }

  private def attributeName(namespaces: Map[Prefix, NamespaceURI]): Parser[TypedQName] = "@" ~> qname(namespaces) ^^ {
    case qn =>
      TypedQName(qn, ComponentType.ATTRIBUTE)
  }

  private def elementComponent(namespaces: Map[Prefix, NamespaceURI]) = elementName(namespaces) ~ rep(predicate(namespaces)) ^^ {
    case element ~ predicates =>
      Component(element, predicates)
  }

  private def elementName(namespaces: Map[Prefix, NamespaceURI]) = qname(namespaces) ^^ {
    case qn =>
      TypedQName(qn, ComponentType.ELEMENT)
  }

  private def qname(namespaces: Map[Prefix, NamespaceURI]) = opt(qnamePrefix <~ ":") ~ qnameLocalPart ^^ {
    case Some(prefix) ~ local =>
      val ns = namespaces.get(prefix).getOrElse(XMLConstants.NULL_NS_URI)
      new QName(ns, local, prefix)
    case None ~ local =>
      new QName(local)
  }

  private def qnamePrefix = """[a-zA-Z0-9\-]+""".r

  private def qnameLocalPart = """[a-zA-Z0-9\-]+""".r

  private def predicate(namespaces: Map[Prefix, NamespaceURI]): Parser[Predicate] = "[" ~> predicateLeftExpression(namespaces) ~ predicateOperator ~ predicateRightExpression <~ "]" ^^ {
    case left ~ op ~ right =>
      Predicate(left, op, Seq(right))
  }

  private def predicateLeftExpression(namespaces: Map[Prefix, NamespaceURI]) = attributeName(namespaces)

  private def predicateOperator: Parser[ComparisonOperator] = atomicEqualsOperator | atomicNotEqualsOperator | sequenceEqualsOperator

  private def atomicEqualsOperator: Parser[ComparisonOperator] = "eq" ^^^ {
    AtomicEqualsComparison
  }

  private def atomicNotEqualsOperator = "ne" ^^^ {
    AtomicNotEqualsComparison
  }

  private def sequenceEqualsOperator = "=" ^^^ {
    SequenceEqualsComparison
  }

  private def predicateRightExpression = predicateRightLiteral

  private def predicateRightLiteral = "'" ~> """[^'"]*""".r <~ "'"
}
