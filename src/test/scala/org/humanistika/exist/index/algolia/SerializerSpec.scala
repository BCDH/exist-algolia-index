package org.humanistika.exist.index.algolia

import Serializer.{serializeElementForAttribute, serializeElementForObject}
import DOMHelper._

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

import org.specs2.Specification
import org.w3c.dom.{Document, Element, Node}
import resource.managed

import scala.util.{Failure, Success}
import scalaz.\/-

class SerializerSpec extends Specification { def is = s2"""
    This is a specification to check the JSON Serialization of XML nodes

      Serialize DOM Element for Algolia Attribute must
        serialize the text node of an element $e1
        serialize the text node of all descendant elements $e2
        ignore attributes of an element when serializing $e3
        ignore attributes of descendant elements when serializing $e4

      Serialize DOM Element for Algolia Object must
        serialize the text node of an element as a field $e5
        serialize the text node of all descendant elements $e6
        include attributes of an element when serializing $e7
        ignore attributes of descendant elements when serializing $e8
        complex test 1 $e9
    """


  def e1 = {
    val elem1 = elem(dom("""<w>hello</w>"""), "w")
    serializeElementForAttribute(elem1) mustEqual \/-("hello")
  }

  def e2 = {
    val elem1 = elem(dom("""<x>hello <b>world<c> again</c></b></x>"""), "x")
    serializeElementForAttribute(elem1) mustEqual \/-("hello world again")
  }

  def e3 = {
    val elem1 = elem(dom("""<w a1="goodbye">hello</w>"""), "w")
    serializeElementForAttribute(elem1) mustEqual \/-("hello")
  }

  def e4 = {
    val elem1 = elem(dom("""<x>hello <b a2="what?">world<c a3="hmm!"> again</c></b></x>"""), "x")
    serializeElementForAttribute(elem1) mustEqual \/-("hello world again")
  }

  def e5 = {
    val elem1 = elem(dom("""<w>hello</w>"""), "w")
    serializeElementForObject("my-object", Map.empty, Map.empty)(elem1) mustEqual \/-(""""#text":"hello"""")
  }

  def e6 = {
    val elem1 = elem(dom("""<x>hello <b>world<c> again</c></b></x>"""), "x")
    serializeElementForObject("my-object", Map.empty, Map.empty)(elem1) mustEqual \/-("hello world again")
  }

  def e7 = {
    val elem1 = elem(dom("""<w a1="goodbye">hello</w>"""), "w")
    serializeElementForObject("my-object", Map.empty, Map.empty)(elem1) mustEqual \/-(""""a1":"goodbye","#text":"hello"""")
  }

  def e8 = {
    val elem1 = elem(dom("""<x>hello <b a2="what?">world<c a3="hmm!"> again</c></b></x>"""), "x")
    serializeElementForObject("my-object", Map.empty, Map.empty)(elem1) mustEqual \/-("hello world again")
  }

  def e9 = {
    val elem1 = elem(dom("""<etym>(<lang value="tr">тур.</lang><mentioned xml:lang="tr">cüce</mentioned>)</etym>"""), "etym")
    serializeElementForObject("my-object", Map.empty, Map.empty)(elem1) mustEqual \/-("hello world again")
  }

  private lazy val documentBuilderFactory = DocumentBuilderFactory.newInstance()
  private def dom(xml: String) : Document = {
    val documentBuilder = documentBuilderFactory.newDocumentBuilder()
    managed(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).map { is =>
      documentBuilder.parse(is)
    }.tried match {
      case Success(s) =>
        s
      case Failure(t) =>
        throw t
    }
  }

  private def elem(node: Node, name: String) : Element = {
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
}
