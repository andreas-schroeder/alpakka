/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.jms.impl
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration._

class SoftReferenceCacheSpec extends WordSpec with Matchers {

  "soft reference cache lookup" should {
    "return default value on miss" in {
      val cache = new SoftReferenceCache[Int, String]
      cache.lookup(1, "one") shouldBe "one"
    }

    "return previous value on hit" in {
      System.gc() // if memory pressure exists, reduce it now.
      val cache = new SoftReferenceCache[Int, String]
      cache.lookup(1, "one")
      cache.lookup(1, "two") shouldBe "one"
    }

    "not evaluate default value on hit" in {
      System.gc() // if memory pressure exists, reduce it now.
      val cache = new SoftReferenceCache[Int, String]
      cache.lookup(1, "one")
      cache.lookup(1, throw new RuntimeException("Should not be evaluated")) shouldBe "one"
    }

    "remove entries on garbage collection" in {
      val cache = new SoftReferenceCache[Int, Array[Byte]]

      val deadline = System.currentTimeMillis() + 1.minute.toMillis // try for 1 minute.
      var i = 1

      def addCacheEntries(): Unit = for (_ <- 1 to 40) {
        val array = new Array[Byte](1024 * 1024)
        cache.lookup(i, array)
        i += 1
      }

      val newValue = Array.fill(1024)(1.toByte)

      // detect eviction by inserting a different value into the cache for a previously set key.
      def entryEvicted(index: Int): Boolean = cache.lookup(index, newValue).length == 1024

      def noEntryEvicted: Boolean = !(1 until i).exists(entryEvicted)

      while (noEntryEvicted && System.currentTimeMillis() < deadline) {
        addCacheEntries()
        System.gc()
      }

      noEntryEvicted shouldBe false
    }

    "makes entries visible across threads" in {
      System.gc() // if memory pressure exists, reduce it now.
      val cache = new SoftReferenceCache[Int, String]
      val ex = Executors.newFixedThreadPool(32)
      implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(ex)

      val failure = new AtomicBoolean()

      cache.lookup(1, "1")

      def chain(predecessor: Future[Any], item: Int): Future[Any] =
        if (item == 100000)
          predecessor
        else
          chain(
            predecessor.map { _ =>
              // set own value
              cache.lookup(item, item.toString)
              // lookup value to be set by a predecessor thread and try to inject a wrong value
              val value = cache.lookup(item - 1, "wrong")
              if (value != (item - 1).toString) {
                // successfully injected wron value, that is a test failure.
                info(s"cache has value '$value' for entry ${item - 1}, expected '${item - 1}'")
                failure.set(true)
              }
            },
            item + 1
          )
      try {
        Await.ready(chain(Future.successful(""), 2), 10.seconds)
      } finally {
        ex.shutdownNow()
      }

      failure.get() shouldBe false
    }
  }
}
