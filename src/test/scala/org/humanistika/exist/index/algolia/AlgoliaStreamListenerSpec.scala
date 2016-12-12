package org.humanistika.exist.index.algolia

import org.specs2.Specification
import AlgoliaStreamListener._
import org.exist.storage.NodePath

class AlgoliaStreamListenerSpec  extends Specification { def is = s2"""
  This is a specification to check the AlgoliaStreamListener

    startsWith
      returns true when path starts with other path $e1
      returns true when path starts with other root path $e2
      returns true when paths are equal $e3
      returns false when path does not start with other path $e4
  """

  private val ns = {
    val map = new java.util.HashMap[String, String]
    map.put("tei", "http://www.tei-c.org/ns/1.0")
    map
  }

  def e1 = {
    val rootObjectPath = new NodePath(ns,   "/tei:TEI/tei:text/tei:body/tei:div/tei:entryFree")
    val attributePath = new NodePath(ns,    "/tei:TEI/tei:text/tei:body/tei:div/tei:entryFree/tei:form/tei:orth/@norm")
    attributePath.startsWith(rootObjectPath) must beTrue
  }

  def e2 = {
    val rootObjectPath = new NodePath(ns,   "")
    val attributePath = new NodePath(ns,    "/tei:TEI/tei:text/tei:body/tei:div/tei:entryFree/tei:form/tei:orth/@norm")
    attributePath.startsWith(rootObjectPath) must beTrue
  }

  def e3 = {
    val rootObjectPath = new NodePath(ns,   "/tei:TEI/tei:text/tei:body/tei:div/tei:entryFree")
    val attributePath = new NodePath(ns,    "/tei:TEI/tei:text/tei:body/tei:div/tei:entryFree")
    attributePath.startsWith(rootObjectPath) must beTrue
  }

  def e4 = {
    val rootObjectPath = new NodePath(ns,   "/x/y/z")
    val attributePath = new NodePath(ns,    "/tei:TEI/tei:text/tei:body/tei:div/tei:entryFree/tei:form/tei:orth/@norm")
    attributePath.startsWith(rootObjectPath) must beFalse
  }

}
