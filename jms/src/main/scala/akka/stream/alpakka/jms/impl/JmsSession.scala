/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.jms.impl
import java.util.concurrent.ArrayBlockingQueue

import akka.annotation.InternalApi
import akka.stream.alpakka.jms.{Destination, DurableTopic, TxEnvelope}
import javax.jms

import scala.concurrent.{ExecutionContext, Future}

@InternalApi private[jms] trait JmsSession {

  def connection: jms.Connection

  def session: jms.Session

  private[jms] def closeSessionAsync()(implicit ec: ExecutionContext): Future[Unit] = Future { closeSession() }

  private[jms] def closeSession(): Unit = session.close()

  private[jms] def abortSessionAsync()(implicit ec: ExecutionContext): Future[Unit] = Future { abortSession() }

  private[jms] def abortSession(): Unit = closeSession()
}

@InternalApi private[jms] class JmsProducerSession(val connection: jms.Connection,
                                                   val session: jms.Session,
                                                   val jmsDestination: Option[jms.Destination])
    extends JmsSession

@InternalApi private[jms] class JmsConsumerSession(val connection: jms.Connection,
                                                   val session: jms.Session,
                                                   val jmsDestination: jms.Destination,
                                                   val settingsDestination: Destination)
    extends JmsSession {

  private[jms] def createConsumer(
      selector: Option[String]
  )(implicit ec: ExecutionContext): Future[jms.MessageConsumer] =
    Future {
      (selector, settingsDestination) match {
        case (None, t: DurableTopic) =>
          session.createDurableSubscriber(jmsDestination.asInstanceOf[jms.Topic], t.subscriberName)

        case (Some(expr), t: DurableTopic) =>
          session.createDurableSubscriber(jmsDestination.asInstanceOf[jms.Topic], t.subscriberName, expr, false)

        case (Some(expr), _) =>
          session.createConsumer(jmsDestination, expr)

        case (None, _) =>
          session.createConsumer(jmsDestination)
      }
    }
}

@InternalApi private[jms] final case class StopMessageListenerException() extends Exception("Stopping MessageListener.")

@InternalApi private[jms] class JmsAckSession(override val connection: jms.Connection,
                                              override val session: jms.Session,
                                              override val jmsDestination: jms.Destination,
                                              override val settingsDestination: Destination,
                                              val maxPendingAcks: Int)
    extends JmsConsumerSession(connection, session, jmsDestination, settingsDestination) {

  private[jms] var pendingAck = 0
  private[jms] val ackQueue = new ArrayBlockingQueue[() => Unit](maxPendingAcks + 1)

  def ack(message: jms.Message): Unit = ackQueue.put(message.acknowledge _)

  override def closeSession(): Unit = stopMessageListenerAndCloseSession()

  override def abortSession(): Unit = stopMessageListenerAndCloseSession()

  private def stopMessageListenerAndCloseSession(): Unit = {
    ackQueue.put(() => throw StopMessageListenerException())
    session.close()
  }
}

@InternalApi private[jms] class JmsTxSession(override val connection: jms.Connection,
                                             override val session: jms.Session,
                                             override val jmsDestination: jms.Destination,
                                             override val settingsDestination: Destination)
    extends JmsConsumerSession(connection, session, jmsDestination, settingsDestination) {

  private[jms] val commitQueue = new ArrayBlockingQueue[TxEnvelope => Unit](1)

  def commit(commitEnv: TxEnvelope): Unit = commitQueue.put { srcEnv =>
    require(srcEnv == commitEnv, s"Source envelope mismatch on commit. Source: $srcEnv Commit: $commitEnv")
    session.commit()
  }

  def rollback(commitEnv: TxEnvelope): Unit = commitQueue.put { srcEnv =>
    require(srcEnv == commitEnv, s"Source envelope mismatch on rollback. Source: $srcEnv Commit: $commitEnv")
    session.rollback()
  }

  override def abortSession(): Unit = {
    // On abort, tombstone the onMessage loop to stop processing messages even if more messages are delivered.
    commitQueue.put(_ => throw StopMessageListenerException())
    session.close()
  }
}
