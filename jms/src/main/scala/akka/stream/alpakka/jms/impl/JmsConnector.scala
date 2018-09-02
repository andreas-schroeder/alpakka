/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.jms.impl

import akka.annotation.InternalApi
import akka.stream.alpakka.jms._
import akka.stream.stage.{AsyncCallback, GraphStageLogic}
import akka.stream.{ActorAttributes, ActorMaterializer, Attributes}
import javax.jms

import scala.concurrent.{ExecutionContext, Future}

@InternalApi private[jms] trait JmsConnector[S <: JmsSession] { this: GraphStageLogic =>

  implicit protected var ec: ExecutionContext = _

  protected var jmsConnection: Option[jms.Connection] = None

  protected var jmsSessions = Seq.empty[S]

  protected def jmsSettings: JmsSettings

  protected def onSessionOpened(jmsSession: S): Unit = {}

  protected val fail: AsyncCallback[Throwable] = getAsyncCallback[Throwable](e => failStage(e))

  private val onConnection: AsyncCallback[jms.Connection] = getAsyncCallback[jms.Connection] { c =>
    jmsConnection = Some(c)
  }

  private val onSession: AsyncCallback[S] = getAsyncCallback[S] { session =>
    jmsSessions :+= session
    onSessionOpened(session)
  }

  protected def executionContext(attributes: Attributes): ExecutionContext = {
    val dispatcher = attributes.get[ActorAttributes.Dispatcher](
      ActorAttributes.Dispatcher("akka.stream.default-blocking-io-dispatcher")
    ) match {
      case ActorAttributes.Dispatcher("") =>
        ActorAttributes.Dispatcher("akka.stream.default-blocking-io-dispatcher")
      case d => d
    }

    materializer match {
      case m: ActorMaterializer => m.system.dispatchers.lookup(dispatcher.dispatcher)
      case x => throw new IllegalArgumentException(s"Stage only works with the ActorMaterializer, was: $x")
    }
  }

  protected def initSessionAsync(executionContext: ExecutionContext): Future[Unit] = {
    ec = executionContext
    val future = Future {
      val sessions = openSessions()
      sessions foreach { session =>
        onSession.invoke(session)
      }
    }
    future.failed.foreach(e => fail.invoke(e))
    future
  }

  def openSessions(): Seq[S]

  def openConnection(): jms.Connection = {
    val factory = jmsSettings.connectionFactory
    val connection = jmsSettings.credentials match {
      case Some(Credentials(username, password)) => factory.createConnection(username, password)
      case _ => factory.createConnection()
    }
    connection.setExceptionListener(new jms.ExceptionListener {
      override def onException(exception: jms.JMSException): Unit =
        fail.invoke(exception)
    })
    onConnection.invoke(connection)
    connection
  }
}

private[jms] trait JmsConsumerConnector extends JmsConnector[JmsConsumerSession] { this: GraphStageLogic =>

  protected def createSession(connection: jms.Connection,
                              createDestination: jms.Session => jms.Destination): JmsConsumerSession

  def openSessions(): Seq[JmsConsumerSession] = {
    val connection = openConnection()
    connection.start()

    val createDestination = jmsSettings.destination match {
      case Some(destination) => destination.create
      case _ => throw new IllegalArgumentException("Destination is missing")
    }

    for (_ <- 0 until jmsSettings.sessionCount)
      yield createSession(connection, createDestination)
  }
}

private[jms] trait JmsProducerConnector extends JmsConnector[JmsProducerSession] { this: GraphStageLogic =>

  def openSessions(): Seq[JmsProducerSession] = {
    val connection = openConnection()

    val maybeDestinationFactory = jmsSettings.destination.map(_.create)

    for (_ <- 0 until jmsSettings.sessionCount)
      yield {
        val session = connection.createSession(false, AcknowledgeMode.AutoAcknowledge.mode)
        new JmsProducerSession(connection, session, maybeDestinationFactory.map(_.apply(session)))
      }
  }
}
