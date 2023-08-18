package org.humanistika.exist.index.algolia

import Serializer.{serializeElementForAttribute, serializeElementForObject}
import DOMHelper._
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global    // TODO(AR) switch to using cats.effect.IOApp
import cats.syntax.either._
import javax.xml.parsers.DocumentBuilderFactory
import org.specs2.Specification
import org.w3c.dom.{Document, Element, Node}

class SerializerSpec extends Specification { def is = s2"""
    This is a specification to check the JSON Serialization of XML nodes

       Serialize DOM Element for Algolia Attribute must
        serialize the text node of an element $e1
        serialize the text node of all descendant elements $e2
        ignore attributes of an element when serializing $e3
        ignore attributes of descendant elements when serializing $e4

      Serialize DOM Element for Algolia Object must
        serialize the text node of an element as a field $e5
        serialize the text nodes of all descendant elements $e6
        include attributes of an element when serializing $e7
        include attributes of descendant elements when serializing $e8
        include mixed content text nodes when serializing $e9
        include descendants when serializing $e10
    """


  def e1 = {
    val elem1 = elem(dom("""<w>hello</w>"""), "w")
    serializeElementForAttribute(elem1) mustEqual Right("hello")
  }

  def e2 = {
    val elem1 = elem(dom("""<x>hello <b>world<c> again</c></b></x>"""), "x")
    serializeElementForAttribute(elem1) mustEqual Right("hello world again")
  }

  def e3 = {
    val elem1 = elem(dom("""<w a1="goodbye">hello</w>"""), "w")
    serializeElementForAttribute(elem1) mustEqual Right("hello")
  }

  def e4 = {
    val elem1 = elem(dom("""<x>hello <b a2="what?">world<c a3="hmm!"> again</c></b></x>"""), "x")
    serializeElementForAttribute(elem1) mustEqual Right("hello world again")
  }

  def e5 = {
    val elem1 = elem(dom("""<w>hello</w>"""), "w")
    serializeElementForObject("my-object", Map.empty, Map.empty)(elem1) mustEqual Right(""","#text":"hello"""")
  }

  def e6 = {
    val elem1 = elem(dom("""<x>hello <b>world<c> again</c></b></x>"""), "x")
    serializeElementForObject("my-object", Map.empty, Map.empty)(elem1) mustEqual Right(""","#text":"hello ","b":{"#text":"world","c":" again"}""")
  }

  def e7 = {
    val elem1 = elem(dom("""<w a1="goodbye">hello</w>"""), "w")
    serializeElementForObject("my-object", Map.empty, Map.empty)(elem1) mustEqual Right(""","a1":"goodbye","#text":"hello"""")
  }

  def e8 = {
    val elem1 = elem(dom("""<x>hello <b a2="what?">world<c a3="hmm!"> again</c></b> yup</x>"""), "x")
    serializeElementForObject("my-object", Map.empty, Map.empty)(elem1) mustEqual Right(""","#text":["hello "," yup"],"b":{"a2":"what?","#text":"world","c":{"a3":"hmm!","#text":" again"}}""")
  }

  def e9 = {
    val elem1 = elem(dom("""<etym>(<lang value="tr">тур.</lang><mentioned xml:lang="tr">cüce</mentioned>)</etym>"""), "etym")
    serializeElementForObject("my-object", Map.empty, Map.empty)(elem1) mustEqual Right(""","#text":["(",")"],"lang":{"value":"tr","#text":"тур."},"mentioned":{"xml:lang":"tr","#text":"cüce"}""")
  }

  def e10 = {
    val elem1 = elem(dom("""<etym source="#thirdEd"><lang value="tr">[*]</lang></etym>"""), "etym")
    serializeElementForObject("my-object", Map.empty, Map.empty)(elem1) mustEqual Right(""","source":"#thirdEd","lang":{"value":"tr","#text":"[*]"}""")
  }

  private lazy val documentBuilderFactory = DocumentBuilderFactory.newInstance()
  private def dom(xml: String) : Document = {
    val documentBuilder = documentBuilderFactory.newDocumentBuilder()
    Resource.fromAutoCloseable(IO {new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))}).use { is =>
      IO {
        documentBuilder.parse(is)
      }
    }.redeem(_.asLeft, _.asRight).unsafeRunSync() match {
      case Right(s) =>
        s
      case Left(t) =>
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
