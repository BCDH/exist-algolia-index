package org.humanistika.exist.index.algolia

/**
  * Created by aretter on 07/05/2017 from http://blog.xebia.com/testing-akka-with-specs2/
  */
import org.specs2.mutable._
import akka.actor._
import akka.testkit._

import scala.concurrent.Await
import scala.concurrent.duration._

/* A tiny class that can be used as a Specs2 'context'. */
abstract class AkkaTestkitSpecs2Support extends TestKit(ActorSystem())
  with After
  with ImplicitSender {

  // make sure we shut down the actor system after all tests have run
  override def after = Await.result(system.terminate(), Duration.Inf)
}
