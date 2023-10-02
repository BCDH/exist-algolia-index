package org.humanistika.exist.index.algolia

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.{Attr, Document, Element, Node}

import scala.annotation.tailrec

object DOMHelper {

  private lazy val documentBuilderFactory = DocumentBuilderFactory.newInstance()

  def dom(xml: String) : Document = {
    val documentBuilder = documentBuilderFactory.newDocumentBuilder()

    With(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) { is =>
        documentBuilder.parse(is)
    }.get
  }

  def attr(node: Node, name: String) : Attr = {
    if(node.isInstanceOf[Element]) {
      node.asInstanceOf[Element].getAttributeNode(name)
    } else if(node.isInstanceOf[Attr]) {
      node.asInstanceOf[Attr]
    } else if(node.isInstanceOf[Document]) {
      attr(node.asInstanceOf[Document].getDocumentElement, name)
    } else {
      throw new IllegalArgumentException
    }
  }

  /**
    * Find all sibling elements by name.
    *
    * Does not descend within the node, except
    * for a document node which will be expanded to the document element.
    */
  def elem(node: Node, name: String) : Element = {
    if(node.isInstanceOf[Element]) {
      val e = node.asInstanceOf[Element]
      if(Option(e.getLocalName).getOrElse(e.getNodeName) == name) {
        return e
      } else {
        val next = e.getNextSibling
        if(next != null) {
          return elem(next, name)
        }
      }
    } else if(node.isInstanceOf[Document]) {
      return elem(node.asInstanceOf[Document].getDocumentElement, name)
    }

    throw new IllegalArgumentException
  }

  def childElem(elem: Element, name: String) : Element = {
    val childNodes = elem.getChildNodes
    (0 until childNodes.getLength)
      .map(childNodes.item(_))
      .filter(_.getNodeType == Node.ELEMENT_NODE)
      .map(_.asInstanceOf[Element])
      .filter(child => Option(child.getLocalName).getOrElse(child.getNodeName) == name)
      .headOption.getOrElse(null)
  }

  /**
    * Find all self-or-descendant elements by name.
    */
  def elems(node: Node, name: String) : Seq[Element] = {
    if(node.isInstanceOf[Element]) {
      val e = node.asInstanceOf[Element]
      if(Option(e.getLocalName).getOrElse(e.getNodeName) == name) {
        Option(e.getNextSibling) match {
          case Some(sibling) if(Option(e.getLocalName).getOrElse(e.getNodeName) == name) =>
            return Seq(e) ++ elems(sibling, name)
          case None =>
            return Seq(e)
        }
      } else {
        val next = Option(e.getNextSibling).getOrElse(e.getFirstChild)
        if(next != null) {
          return elems(next, name)
        }
      }
    } else if(node.isInstanceOf[Document]) {
      return elems(node.asInstanceOf[Document].getDocumentElement, name)
    }

    throw new IllegalArgumentException
  }

  @tailrec
  def firstElem(node: Node, name: String) : Option[Element] = {
    if(node.isInstanceOf[Element]) {
      val e = node.asInstanceOf[Element]
      if (Option(e.getLocalName).getOrElse(e.getNodeName) == name) {
        return Some(e)
      }
    }

    val next =
      Option(node.getNextSibling)
        .orElse(Option(node.getFirstChild))

    next match {
      case None => None
      case Some(n) => firstElem(n, name)
    }
  }
}
