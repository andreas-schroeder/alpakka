/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.jms
import java.util.concurrent.atomic.AtomicBoolean

import akka.stream.alpakka.jms.impl.{JmsAckSession, JmsTxSession}
import javax.jms.Message

class AckEnvelope private[jms] (val message: Message, private val jmsSession: JmsAckSession) {

  val processed = new AtomicBoolean(false)

  def acknowledge(): Unit = if (processed.compareAndSet(false, true)) jmsSession.ack(message)
}

class TxEnvelope private[jms] (val message: Message, private val jmsSession: JmsTxSession) {

  val processed = new AtomicBoolean(false)

  def commit(): Unit = if (processed.compareAndSet(false, true)) jmsSession.commit(this)

  def rollback(): Unit = if (processed.compareAndSet(false, true)) jmsSession.rollback(this)
}
