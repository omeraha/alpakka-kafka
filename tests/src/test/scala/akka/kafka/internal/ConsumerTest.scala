/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 - 2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.kafka.internal

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage._
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.Control
import akka.kafka.{CommitTimeoutException, ConsumerSettings, Subscriptions}
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object ConsumerTest {
  type K = String
  type V = String
  type Record = ConsumerRecord[K, V]

  val closeTimeout = 500.millis

  def createMessage(seed: Int): CommittableMessage[K, V] = createMessage(seed, "topic")

  def createMessage(seed: Int,
                    topic: String,
                    groupId: String = "group1",
                    metadata: String = ""): CommittableMessage[K, V] = {
    val offset = PartitionOffset(GroupTopicPartition(groupId, topic, 1), seed.toLong)
    val record = new ConsumerRecord(offset.key.topic, offset.key.partition, offset.offset, seed.toString, seed.toString)
    CommittableMessage(record, CommittableOffsetImpl(offset, metadata)(null))
  }

  def toRecord(msg: CommittableMessage[K, V]): ConsumerRecord[K, V] = msg.record
}

class ConsumerTest(_system: ActorSystem)
    extends TestKit(_system)
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  import ConsumerTest._

  def this() = this(ActorSystem())

  override def afterAll(): Unit =
    shutdown(system)

  implicit val m = ActorMaterializer(ActorMaterializerSettings(_system).withFuzzing(true))
  implicit val ec = _system.dispatcher
  val messages = (1 to 1000).map(createMessage)

  def checkMessagesReceiving(msgss: Seq[Seq[CommittableMessage[K, V]]]): Unit = {
    val mock = new ConsumerMock[K, V]()
    val (control, probe) = createCommittableSource(mock.mock)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    probe.request(msgss.map(_.size).sum.toLong)
    msgss.foreach(chunk => mock.enqueue(chunk.map(toRecord)))
    probe.expectNextN(msgss.flatten)

    Await.result(control.shutdown(), remainingOrDefault)
  }

  def testSettings(mock: Consumer[K, V], groupId: String = "group1") =
    new ConsumerSettings(
      Map(ConsumerConfig.GROUP_ID_CONFIG -> groupId),
      Some(new StringDeserializer),
      Some(new StringDeserializer),
      pollInterval = 10.millis,
      pollTimeout = 10.millis,
      1.second,
      closeTimeout,
      1.second,
      5.seconds,
      3,
      Duration.Inf,
      "akka.kafka.default-dispatcher",
      1.second,
      true,
      100.millis
    ) {
      override def createKafkaConsumer(): Consumer[K, V] =
        mock
    }

  def createCommittableSource(mock: Consumer[K, V],
                              groupId: String = "group1",
                              topics: Set[String] = Set("topic")): Source[CommittableMessage[K, V], Control] =
    Consumer.committableSource(testSettings(mock, groupId), Subscriptions.topics(topics))

  def createSourceWithMetadata(mock: Consumer[K, V],
                               metadataFromRecord: ConsumerRecord[K, V] => String,
                               groupId: String = "group1",
                               topics: Set[String] = Set("topic")): Source[CommittableMessage[K, V], Control] =
    Consumer.commitWithMetadataSource(testSettings(mock, groupId), Subscriptions.topics(topics), metadataFromRecord)

  it should "fail stream when poll() fails with unhandled exception" in assertAllStagesStopped {
    val mock = new FailingConsumerMock[K, V](new Exception("Fatal Kafka error"), failOnCallNumber = 1)

    val probe = createCommittableSource(mock.mock)
      .toMat(TestSink.probe)(Keep.right)
      .run()

    probe
      .request(1)
      .expectError()
  }

  it should "not fail stream when poll() fails twice with WakeupException" in assertAllStagesStopped {
    val mock = new FailingConsumerMock[K, V](new WakeupException(), failOnCallNumber = 1, 2)

    val probe = createCommittableSource(mock.mock)
      .toMat(TestSink.probe)(Keep.right)
      .run()

    probe
      .request(1)
      .expectNoMessage(200.millis)
      .cancel()
  }

  it should "not fail stream when poll() fails twice, then succeeds, then fails twice with WakeupException" in assertAllStagesStopped {
    val mock = new FailingConsumerMock[K, V](new WakeupException(), failOnCallNumber = 1, 2, 4, 5)

    val probe = createCommittableSource(mock.mock)
      .toMat(TestSink.probe)(Keep.right)
      .run()

    probe
      .request(1)
      .expectNoMessage(200.millis)
      .cancel()
  }

  it should "fail stream when poll() fail limit exceeded" in assertAllStagesStopped {
    val mock = new FailingConsumerMock[K, V](new WakeupException(), failOnCallNumber = 1, 2, 3)

    val probe = createCommittableSource(mock.mock)
      .toMat(TestSink.probe)(Keep.right)
      .run()

    probe
      .request(1)
      .expectError()
  }

  it should "complete stage when stream control.stop called" in assertAllStagesStopped {
    val mock = new ConsumerMock[K, V]()
    val (control, probe) = createCommittableSource(mock.mock)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    probe.request(100)

    Await.result(control.shutdown(), remainingOrDefault)
    probe.expectComplete()
    mock.verifyClosed()
  }

  it should "complete stage when processing flow canceled" in assertAllStagesStopped {
    val mock = new ConsumerMock[K, V]()
    val (control, probe) = createCommittableSource(mock.mock)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    probe.request(100)
    mock.verifyClosed(never())
    probe.cancel()
    Await.result(control.isShutdown, remainingOrDefault)
    mock.verifyClosed()
  }

  it should "emit messages received as one big chunk" in assertAllStagesStopped {
    checkMessagesReceiving(Seq(messages))
  }

  it should "emit messages received as medium chunks" in assertAllStagesStopped {
    checkMessagesReceiving(messages.grouped(97).to[Seq])
  }

  it should "emit messages received as one message per chunk" in assertAllStagesStopped {
    checkMessagesReceiving((1 to 100).map(createMessage).grouped(1).to[Seq])
  }

  it should "emit messages received with empty some messages" in assertAllStagesStopped {
    checkMessagesReceiving(
      messages
        .grouped(97)
        .map(x => Seq(Seq.empty, x))
        .flatten
        .to[Seq]
    )
  }

  it should "complete out and keep underlying client open when control.stop called" in assertAllStagesStopped {
    val commitLog = new ConsumerMock.LogHandler()
    val mock = new ConsumerMock[K, V](commitLog)
    val (control, probe) = createCommittableSource(mock.mock)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    mock.enqueue((1 to 10).map(createMessage).map(toRecord))
    probe.request(1)
    probe.expectNext()

    Await.result(control.stop(), remainingOrDefault)
    probe.expectComplete()

    mock.verifyClosed(never())

    Await.result(control.shutdown(), remainingOrDefault)
    mock.verifyClosed()
  }

  it should "complete stop's Future after stage was shutdown" in assertAllStagesStopped {
    val commitLog = new ConsumerMock.LogHandler()
    val mock = new ConsumerMock[K, V](commitLog)
    val (control, probe) = createCommittableSource(mock.mock)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    probe.request(1)
    Await.result(control.stop(), remainingOrDefault)
    probe.expectComplete()

    Await.result(control.shutdown(), remainingOrDefault)
    Await.result(control.stop(), remainingOrDefault)
  }

  it should "return completed Future in stop after shutdown" in assertAllStagesStopped {
    val commitLog = new ConsumerMock.LogHandler()
    val mock = new ConsumerMock[K, V](commitLog)
    val (control, probe) = createCommittableSource(mock.mock)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    probe.cancel()
    Await.result(control.isShutdown, remainingOrDefault)
    control.stop().value.get.get shouldBe Done
  }

  it should "be ok to call control.stop multiple times" in assertAllStagesStopped {
    val commitLog = new ConsumerMock.LogHandler()
    val mock = new ConsumerMock[K, V](commitLog)
    val (control, probe) = createCommittableSource(mock.mock)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    mock.enqueue((1 to 10).map(createMessage).map(toRecord))
    probe.request(1)
    probe.expectNext()

    val stops = (1 to 5).map(_ => control.stop())
    Await.result(Future.sequence(stops), remainingOrDefault)

    probe.expectComplete()
    Await.result(control.shutdown(), remainingOrDefault)
  }

  it should "keep stage running until all futures completed" in assertAllStagesStopped {
    val commitLog = new ConsumerMock.LogHandler()
    val mock = new ConsumerMock[K, V](commitLog)
    val (control, probe) = createCommittableSource(mock.mock)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    val msgs = (1 to 10).map(createMessage)
    mock.enqueue(msgs.map(toRecord))

    probe.request(100)
    val done = probe.expectNext().committableOffset.commitScaladsl()
    probe.expectNextN(9)

    awaitAssert {
      commitLog.calls should have size (1)
    }

    val stopped = control.shutdown()
    probe.expectComplete()

    Thread.sleep(100)
    stopped.isCompleted should ===(false)

    //emulate commit
    commitLog.calls.foreach {
      case (offsets, callback) => callback.onComplete(offsets.asJava, null)
    }

    Await.result(done, remainingOrDefault)
    Await.result(stopped, remainingOrDefault)
    mock.verifyClosed()
  }

  it should "complete futures with failure when commit after stop" in assertAllStagesStopped {
    val commitLog = new ConsumerMock.LogHandler()
    val mock = new ConsumerMock[K, V](commitLog)
    val (control, probe) = createCommittableSource(mock.mock)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    val msg = createMessage(1)
    mock.enqueue(List(toRecord(msg)))

    probe.request(100)
    val first = probe.expectNext()

    val stopped = control.shutdown()
    probe.expectComplete()
    Await.result(stopped, remainingOrDefault)

    val done = first.committableOffset.commitScaladsl()
    intercept[CommitTimeoutException] {
      Await.result(done, remainingOrDefault)
    }
  }

  it should "keep stage running after cancellation until all futures completed" in assertAllStagesStopped {
    val commitLog = new ConsumerMock.LogHandler()
    val mock = new ConsumerMock[K, V](commitLog)
    val (control, probe) = createCommittableSource(mock.mock)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    val msgs = (1 to 10).map(createMessage)
    mock.enqueue(msgs.map(toRecord))

    probe.request(5)
    val done = probe.expectNext().committableOffset.commitScaladsl()
    probe.expectNextN(4)

    awaitAssert {
      commitLog.calls should have size 1
    }

    probe.cancel()
    probe.expectNoMessage(200.millis)
    control.isShutdown.isCompleted should ===(false)

    //emulate commit
    commitLog.calls.foreach {
      case (offsets, callback) => callback.onComplete(offsets.asJava, null)
    }

    Await.result(done, remainingOrDefault)
    Await.result(control.isShutdown, remainingOrDefault)
    mock.verifyClosed()
  }
}
