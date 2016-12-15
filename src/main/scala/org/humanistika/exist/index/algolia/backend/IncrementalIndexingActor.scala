package org.humanistika.exist.index.algolia.backend

import java.nio.file.Path

import org.humanistika.exist.index.algolia.{IndexName, IndexableRootObject}
import IncrementalIndexingActor._
import akka.actor.Actor


object IncrementalIndexingActor {
  case class Add(index: IndexName, userSpecifiedDocumentId: Option[String], indexableRootObject: IndexableRootObject)
  case class FinishDocument()
}

class IncrementalIndexingActor(dataDir: Path) extends Actor {
  override def receive = {
    case Add(index: IndexName, userSpecifiedDocumentId: Option[String], indexableRootObject: IndexableRootObject) =>

    case FinishDocument() =>
  }
}
