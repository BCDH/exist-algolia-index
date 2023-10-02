package org.humanistika.exist.index.algolia

import scala.util.{Failure, Success, Try}

// TODO(AR) replace with `Using` in Scala 2.13
object With {
  def apply[R, A](acquire: => R)(release: R => Unit)(f: R => A): Try[A] = {
    val r = acquire
    try {
      val a = f(r)
      Success(a)
    } catch {
      case t: Throwable =>
        Failure(t)
    } finally {
      release(r)
    }
  }

  def apply[R <: AutoCloseable, A](acquire: => R)(f: R => A): Try[A] = {
    val r = acquire
    try {
      val a = f(r)
      Success(a)
    } catch {
      case t: Throwable =>
        Failure(t)
    } finally {
      r.close()
    }
  }
}
