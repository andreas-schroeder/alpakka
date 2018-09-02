/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.jms

import java.time.Duration

import javax.jms
import javax.jms.ConnectionFactory

import scala.concurrent.duration._

sealed trait JmsSettings {
  def connectionFactory: ConnectionFactory
  def destination: Option[Destination]
  def credentials: Option[Credentials]
  def sessionCount: Int
}

sealed trait Destination {
  val name: String
  protected[jms] val create: jms.Session => jms.Destination
}

final class Topic(val name: String) extends Destination {
  protected[jms] val create: jms.Session => jms.Destination = _.createTopic(name)
}
object Topic {
  def apply(name: String): Topic = new Topic(name)
}

final class DurableTopic(val name: String, val subscriberName: String) extends Destination {
  protected[jms] val create: jms.Session => jms.Destination = _.createTopic(name)
}
object DurableTopic {
  def apply(name: String, subscriberName: String): DurableTopic = new DurableTopic(name, subscriberName)
}

final class Queue(val name: String) extends Destination {
  protected[jms] val create: jms.Session => jms.Destination = _.createQueue(name)
}
object Queue {
  def apply(name: String): Queue = new Queue(name)
}

final class CustomDestination(val name: String, val create: jms.Session => jms.Destination) extends Destination
object CustomDestination {
  def apply(name: String, create: jms.Session => jms.Destination): CustomDestination =
    new CustomDestination(name, create)
}

final class AcknowledgeMode(val mode: Int)

object AcknowledgeMode {
  val AutoAcknowledge: AcknowledgeMode = new AcknowledgeMode(jms.Session.AUTO_ACKNOWLEDGE)
  val ClientAcknowledge: AcknowledgeMode = new AcknowledgeMode(jms.Session.CLIENT_ACKNOWLEDGE)
  val DupsOkAcknowledge: AcknowledgeMode = new AcknowledgeMode(jms.Session.DUPS_OK_ACKNOWLEDGE)
  val SessionTransacted: AcknowledgeMode = new AcknowledgeMode(jms.Session.SESSION_TRANSACTED)
}

object JmsConsumerSettings {

  def create(connectionFactory: ConnectionFactory): JmsConsumerSettings = JmsConsumerSettings(connectionFactory)

  def apply(connectionFactory: ConnectionFactory): JmsConsumerSettings = new JmsConsumerSettings(connectionFactory)

}

final class JmsConsumerSettings private (val connectionFactory: ConnectionFactory,
                                         val destination: Option[Destination] = None,
                                         val credentials: Option[Credentials] = None,
                                         val sessionCount: Int = 1,
                                         val bufferSize: Int = 100,
                                         val selector: Option[String] = None,
                                         val acknowledgeMode: Option[AcknowledgeMode] = None,
                                         val durableName: Option[String] = None)
    extends JmsSettings {

  def withCredential(credentials: Credentials): JmsConsumerSettings = copy(credentials = Some(credentials))
  def withSessionCount(count: Int): JmsConsumerSettings = copy(sessionCount = count)
  def withBufferSize(size: Int): JmsConsumerSettings = copy(bufferSize = size)
  def withQueue(name: String): JmsConsumerSettings = copy(destination = Some(Queue(name)))
  def withTopic(name: String): JmsConsumerSettings = copy(destination = Some(Topic(name)))
  def withDurableTopic(name: String, subscriberName: String): JmsConsumerSettings =
    copy(destination = Some(DurableTopic(name, subscriberName)))
  def withDestination(destination: Destination): JmsConsumerSettings = copy(destination = Some(destination))
  def withSelector(selector: String): JmsConsumerSettings = copy(selector = Some(selector))
  def withAcknowledgeMode(acknowledgeMode: AcknowledgeMode): JmsConsumerSettings =
    copy(acknowledgeMode = Option(acknowledgeMode))

  private def copy(connectionFactory: ConnectionFactory = connectionFactory,
                   destination: Option[Destination] = destination,
                   credentials: Option[Credentials] = credentials,
                   sessionCount: Int = sessionCount,
                   bufferSize: Int = bufferSize,
                   selector: Option[String] = selector,
                   acknowledgeMode: Option[AcknowledgeMode] = acknowledgeMode,
                   durableName: Option[String] = durableName): JmsConsumerSettings =
    new JmsConsumerSettings(connectionFactory,
                            destination,
                            credentials,
                            sessionCount,
                            bufferSize,
                            selector,
                            acknowledgeMode,
                            durableName)
}

object JmsProducerSettings {

  def create(connectionFactory: ConnectionFactory): JmsProducerSettings = JmsProducerSettings(connectionFactory)

  def apply(connectionFactory: ConnectionFactory): JmsProducerSettings = new JmsProducerSettings(connectionFactory)

}

final class JmsProducerSettings private (val connectionFactory: ConnectionFactory,
                                         val destination: Option[Destination] = None,
                                         val credentials: Option[Credentials] = None,
                                         val sessionCount: Int = 1,
                                         val timeToLive: Option[FiniteDuration] = None)
    extends JmsSettings {
  def withCredential(credentials: Credentials): JmsProducerSettings = copy(credentials = Some(credentials))
  def withSessionCount(count: Int): JmsProducerSettings = copy(sessionCount = count)
  def withQueue(name: String): JmsProducerSettings = copy(destination = Some(Queue(name)))
  def withTopic(name: String): JmsProducerSettings = copy(destination = Some(Topic(name)))
  def withDestination(destination: Destination): JmsProducerSettings = copy(destination = Some(destination))
  def withTimeToLive(ttl: FiniteDuration): JmsProducerSettings = copy(timeToLive = Some(ttl))

  /**
   * Java API
   */
  def withTimeToLive(ttl: Duration): JmsProducerSettings =
    copy(timeToLive = Some(ttl.getSeconds.seconds + ttl.getNano.nanos))

  private def copy(connectionFactory: ConnectionFactory = connectionFactory,
                   destination: Option[Destination] = destination,
                   credentials: Option[Credentials] = credentials,
                   sessionCount: Int = sessionCount,
                   timeToLive: Option[FiniteDuration] = timeToLive): JmsProducerSettings =
    new JmsProducerSettings(connectionFactory, destination, credentials, sessionCount, timeToLive)
}

final case class Credentials(username: String, password: String)

object JmsBrowseSettings {

  def create(connectionFactory: ConnectionFactory): JmsBrowseSettings = JmsBrowseSettings(connectionFactory)

  def apply(connectionFactory: ConnectionFactory): JmsBrowseSettings = new JmsBrowseSettings(connectionFactory)

}

final class JmsBrowseSettings private (val connectionFactory: ConnectionFactory,
                                       val destination: Option[Destination] = None,
                                       val credentials: Option[Credentials] = None,
                                       val selector: Option[String] = None,
                                       val acknowledgeMode: Option[AcknowledgeMode] = None)
    extends JmsSettings {

  override val sessionCount = 1

  def withCredential(credentials: Credentials): JmsBrowseSettings = copy(credentials = Some(credentials))
  def withQueue(name: String): JmsBrowseSettings = copy(destination = Some(Queue(name)))
  def withDestination(destination: Destination): JmsBrowseSettings = copy(destination = Some(destination))
  def withSelector(selector: String): JmsBrowseSettings = copy(selector = Some(selector))
  def withAcknowledgeMode(acknowledgeMode: AcknowledgeMode): JmsBrowseSettings =
    copy(acknowledgeMode = Option(acknowledgeMode))

  private def copy(connectionFactory: ConnectionFactory = connectionFactory,
                   destination: Option[Destination] = destination,
                   credentials: Option[Credentials] = credentials,
                   selector: Option[String] = selector,
                   acknowledgeMode: Option[AcknowledgeMode] = acknowledgeMode): JmsBrowseSettings =
    new JmsBrowseSettings(connectionFactory, destination, credentials, selector, acknowledgeMode)
}
