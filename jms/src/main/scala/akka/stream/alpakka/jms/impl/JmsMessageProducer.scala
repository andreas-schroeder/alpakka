/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.jms.impl
import akka.annotation.InternalApi
import akka.stream.alpakka.jms._
import akka.stream.alpakka.jms.impl.JmsMessageProducer.{DestinationMode, MessageDefinedDestination}
import javax.jms

@InternalApi private[jms] class JmsMessageProducer(jmsProducer: jms.MessageProducer,
                                                   jmsSession: JmsProducerSession,
                                                   mode: DestinationMode) {

  private val destinationCache = new SoftReferenceCache[Destination, jms.Destination]()

  def send(elem: JmsMessage): Unit = {
    val message: jms.Message = createMessage(elem)
    populateMessageProperties(message, elem)

    val (sendHeaders, headersBeforeSend: Set[JmsHeader]) = elem.headers.partition(_.usedDuringSend)
    populateMessageHeader(message, headersBeforeSend)

    val deliveryMode = sendHeaders
      .collectFirst { case x: JmsDeliveryMode => x.deliveryMode }
      .getOrElse(jmsProducer.getDeliveryMode)

    val priority = sendHeaders
      .collectFirst { case x: JmsPriority => x.priority }
      .getOrElse(jmsProducer.getPriority)

    val timeToLive = sendHeaders
      .collectFirst { case x: JmsTimeToLive => x.timeInMillis }
      .getOrElse(jmsProducer.getTimeToLive)

    elem match {
      case directed: JmsDirectedMessage if mode == MessageDefinedDestination =>
        jmsProducer.send(lookup(directed.destination), message, deliveryMode, priority, timeToLive)
      case _ =>
        jmsProducer.send(message, deliveryMode, priority, timeToLive)
    }
  }

  private def lookup(destination: Destination) =
    destinationCache.lookup(destination, destination.create(jmsSession.session))

  private[jms] def createMessage(element: JmsMessage): jms.Message =
    element match {

      case textMessage: JmsAbstractTextMessage => jmsSession.session.createTextMessage(textMessage.body)

      case byteMessage: JmsAbstractByteMessage =>
        val newMessage = jmsSession.session.createBytesMessage()
        newMessage.writeBytes(byteMessage.bytes)
        newMessage

      case mapMessage: JmsAbstractMapMessage =>
        val newMessage = jmsSession.session.createMapMessage()
        populateMapMessage(newMessage, mapMessage)
        newMessage

      case objectMessage: JmsAbstractObjectMessage => jmsSession.session.createObjectMessage(objectMessage.serializable)

    }

  private[jms] def populateMessageProperties(message: javax.jms.Message, jmsMessage: JmsMessage): Unit =
    jmsMessage.properties.foreach {
      case (key, v) =>
        v match {
          case v: String => message.setStringProperty(key, v)
          case v: Int => message.setIntProperty(key, v)
          case v: Boolean => message.setBooleanProperty(key, v)
          case v: Byte => message.setByteProperty(key, v)
          case v: Short => message.setShortProperty(key, v)
          case v: Long => message.setLongProperty(key, v)
          case v: Double => message.setDoubleProperty(key, v)
          case null => throw NullMessageProperty(key, jmsMessage)
          case _ => throw UnsupportedMessagePropertyType(key, v, jmsMessage)
        }
    }

  private def populateMapMessage(message: javax.jms.MapMessage, jmsMessage: JmsAbstractMapMessage): Unit =
    jmsMessage.body.foreach {
      case (key, v) =>
        v match {
          case v: String => message.setString(key, v)
          case v: Int => message.setInt(key, v)
          case v: Boolean => message.setBoolean(key, v)
          case v: Byte => message.setByte(key, v)
          case v: Short => message.setShort(key, v)
          case v: Long => message.setLong(key, v)
          case v: Double => message.setDouble(key, v)
          case v: Array[Byte] => message.setBytes(key, v)
          case null => throw NullMapMessageEntry(key, jmsMessage)
          case _ => throw UnsupportedMapMessageEntryType(key, v, jmsMessage)
        }
    }

  private def populateMessageHeader(message: javax.jms.Message, headers: Set[JmsHeader]): Unit =
    headers.foreach {
      case JmsType(jmsType) => message.setJMSType(jmsType)
      case JmsReplyTo(destination) => message.setJMSReplyTo(destination.create(jmsSession.session))
      case JmsCorrelationId(jmsCorrelationId) => message.setJMSCorrelationID(jmsCorrelationId)
    }
}

@InternalApi private[jms] object JmsMessageProducer {
  def apply(jmsSession: JmsProducerSession, settings: JmsProducerSettings): JmsMessageProducer = {
    val producer = jmsSession.session.createProducer(jmsSession.jmsDestination.orNull)
    if (settings.timeToLive.nonEmpty) {
      producer.setTimeToLive(settings.timeToLive.get.toMillis)
    }
    new JmsMessageProducer(producer, jmsSession, destinationMode(settings))
  }

  def destinationMode(settings: JmsProducerSettings): DestinationMode =
    if (settings.destination.isDefined) ProducerDefinedDestination else MessageDefinedDestination

  sealed trait DestinationMode
  object ProducerDefinedDestination extends DestinationMode
  object MessageDefinedDestination extends DestinationMode

}
