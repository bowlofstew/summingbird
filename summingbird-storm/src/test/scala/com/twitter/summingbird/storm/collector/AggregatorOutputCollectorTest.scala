package com.twitter.summingbird.storm.collector

import backtype.storm.spout.ISpoutOutputCollector
import backtype.storm.tuple.Values
import com.twitter.algebird.Semigroup
import com.twitter.algebird.util.summer.AsyncSummer
import com.twitter.summingbird.online.executor.KeyValueShards
import com.twitter.summingbird.online.option.SummerBuilder
import com.twitter.summingbird.storm.{ Counter, MockedISpoutOutputCollector, TestAggregateOutpoutCollector }
import com.twitter.util.Future
import org.scalatest.WordSpec
import scala.collection.mutable.{ Set => MSet }

class TestAsyncSummer extends AsyncSummer[(Int, Int), Iterable[(Int, Int)]] {
  override def flush: Future[Iterable[(Int, Int)]] = Future.Nil
  override def isFlushed = true
  override def tick = Future.Nil
  override def addAll(vals: TraversableOnce[(Int, Int)]): Future[Iterable[(Int, Int)]] =
    Future(Map(0 -> 10))
}

class AggregatorOutputCollectorTest extends WordSpec {
  def setup(expected: MSet[(Int, Map[_, _], Option[String], Option[Any])]) = {
    val mockCollector: ISpoutOutputCollector = new MockedISpoutOutputCollector
    val validatingCollector = new TestAggregateOutpoutCollector(mockCollector, expected)

    val summerBuilder = new SummerBuilder {
      def getSummer[K, V: Semigroup]: AsyncSummer[(K, V), Map[K, V]] =
        (new TestAsyncSummer).asInstanceOf[AsyncSummer[(K, V), Map[K, V]]]
    }

    val aggregatorCollector = new AggregatorOutputCollector(
      validatingCollector,
      summerBuilder,
      KeyValueShards(10),
      Counter("flush"),
      Counter("execTime")
    )(Semigroup.intSemigroup)

    (aggregatorCollector, validatingCollector)
  }

  "Yields addAll result with no stream/message ID" in {
    val expectedTuples = MSet[(Int, Map[_, _], Option[String], Option[Any])]()
    expectedTuples.add((0, Map(0 -> 10), None, None))
    val (aggregator, validator) = setup(expectedTuples)
    aggregator.emit(new Values((4, 5).asInstanceOf[AnyRef]))
    assert(validator.getSize == 0)
  }

  "Yields addAll result with the specified stream ID" in {
    val expectedTuples = MSet[(Int, Map[_, _], Option[String], Option[Any])]()
    expectedTuples.add((0, Map(0 -> 10), Some("foo"), None))
    val (aggregator, validator) = setup(expectedTuples)
    aggregator.emit("foo", new Values((4, 5).asInstanceOf[AnyRef]))
    assert(validator.getSize == 0)
  }

  "Yields addAll result with the associated message ID" in {
    val expectedTuples = MSet[(Int, Map[_, _], Option[String], Option[Any])]()
    expectedTuples.add((0, Map(0 -> 10), None, Some(List("messageId"))))
    val (aggregator, validator) = setup(expectedTuples)
    aggregator.emit(new Values((0, 5).asInstanceOf[AnyRef]), "messageId")
    assert(validator.getSize == 0)
  }

  "Doesn't return message ID from colliding key" in {
    val expectedTuples = MSet[(Int, Map[_, _], Option[String], Option[Any])]()
    expectedTuples.add((0, Map(0 -> 10), None, None))
    val (aggregator, validator) = setup(expectedTuples)
    aggregator.emit(new Values((10, 5).asInstanceOf[AnyRef]), "messageId")
    assert(validator.getSize == 0)
  }
}