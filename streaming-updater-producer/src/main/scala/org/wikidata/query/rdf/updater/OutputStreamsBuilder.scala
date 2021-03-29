package org.wikidata.query.rdf.updater

import java.util.Properties

import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.apache.flink.api.common.time.Time
import org.apache.flink.core.fs.Path
import org.apache.flink.formats.parquet.avro.ParquetAvroWriters
import org.apache.flink.streaming.api.CheckpointingMode
import org.apache.flink.streaming.api.functions.sink.{DiscardingSink, SinkFunction}
import org.apache.flink.streaming.api.functions.sink.filesystem.StreamingFileSink
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.OnCheckpointRollingPolicy
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer
import org.wikidata.query.rdf.updater.config.{HttpClientConfig, UpdaterPipelineOutputStreamConfig}

case class OutputStreams(
                          mutationSink: SinkFunction[MutationDataChunk],
                          lateEventsSink: SinkFunction[InputEvent] = new DiscardingSink[InputEvent],
                          spuriousEventsSink: SinkFunction[IgnoredMutation] = new DiscardingSink[IgnoredMutation],
                          failedOpsSink: SinkFunction[FailedOp] = new DiscardingSink[FailedOp]
                        )

class OutputStreamsBuilder(outputStreamsConfig: UpdaterPipelineOutputStreamConfig, httpClientConfig: HttpClientConfig) {
  def lateEventsOutput: SinkFunction[InputEvent] = {
    outputStreamsConfig.lateEventOutputDir match {
      case Some(dir) => wrapGenericRecordSinkFunction(prepareErrorTrackingFileSink(dir, InputEventEncoder.schema()), InputEventEncoder.map)
      case _ => prepareSideOutputStream[InputEvent](JsonEncoders.lapsedActionStream, JsonEncoders.lapsedActionSchema,
        outputStreamsConfig.schemaRepos, httpClientConfig)
    }
  }

  def spuriousEventsOutput: SinkFunction[IgnoredMutation] = {
    outputStreamsConfig.spuriousEventOutputDir match {
      case Some(dir) => wrapGenericRecordSinkFunction(prepareErrorTrackingFileSink(dir, IgnoredMutationEncoder.schema()), IgnoredMutationEncoder.map)
      case _ => prepareSideOutputStream[IgnoredMutation](JsonEncoders.stateInconsistencyStream, JsonEncoders.stateInconsistencySchema,
        outputStreamsConfig.schemaRepos, httpClientConfig)
    }
  }

  def failedOpOutput: SinkFunction[FailedOp] = {
    outputStreamsConfig.failedEventOutputDir match {
      case Some(dir) => wrapGenericRecordSinkFunction(prepareErrorTrackingFileSink(dir, FailedOpEncoder.schema()), FailedOpEncoder.map)
      case _ => prepareSideOutputStream[FailedOp](JsonEncoders.fetchFailureStream, JsonEncoders.fetchFailureSchema,
        outputStreamsConfig.schemaRepos, httpClientConfig)
    }
  }

  def build: OutputStreams = {
    OutputStreams(mutationOutput, lateEventsOutput, spuriousEventsOutput, failedOpOutput)
  }

  private def prepareSideOutputStream[E](stream: String, schema: String, schemaRepos: List[String], httpClientConfig: HttpClientConfig): SinkFunction[E] = {
    val producerConfig = new Properties()
    producerConfig.setProperty("bootstrap.servers", outputStreamsConfig.sideOutputsKafkaBrokers.getOrElse(outputStreamsConfig.kafkaBrokers))
    val topic = outputStreamsConfig.outputTopicPrefix.getOrElse("") + stream
    new FlinkKafkaProducer[E](
      topic,
      new SideOutputSerializationSchema[E](None, topic, stream, schema, outputStreamsConfig.sideOutputsDomain,
        outputStreamsConfig.eventStreamConfigEndpoint, schemaRepos, httpClientConfig),
      producerConfig,
      // force at least once semantic (WMF event platform does not seem to support kafka transactions yet)
      FlinkKafkaProducer.Semantic.AT_LEAST_ONCE)
  }

  def mutationOutput: SinkFunction[MutationDataChunk] = {
    val producerConfig = new Properties()
    producerConfig.setProperty("bootstrap.servers", outputStreamsConfig.kafkaBrokers)
    // Flink defaults is 1hour but wmf kafka uses the default value of 15min for transaction.max.timeout.ms
    val txTimeoutMs = Time.minutes(15).toMilliseconds
    producerConfig.setProperty("transaction.timeout.ms", txTimeoutMs.toString)
    producerConfig.setProperty("delivery.timeout.ms", txTimeoutMs.toString)
    producerConfig.setProperty("batch.size", "250000")
    producerConfig.setProperty("compression.type", "gzip")
    new FlinkKafkaProducer[MutationDataChunk](
      outputStreamsConfig.topic,
      new MutationEventDataSerializationSchema(outputStreamsConfig.topic, outputStreamsConfig.partition),
      producerConfig,
      outputStreamsConfig.checkpointingMode match {
        case CheckpointingMode.EXACTLY_ONCE => FlinkKafkaProducer.Semantic.EXACTLY_ONCE
        case CheckpointingMode.AT_LEAST_ONCE => FlinkKafkaProducer.Semantic.AT_LEAST_ONCE
      })
  }

  private def wrapGenericRecordSinkFunction[I](sinkFunction: SinkFunction[GenericRecord], mapper: I => GenericRecord): SinkFunction[I] = {
    new SinkFunction[I] {
      override def invoke(value: I, ctx: SinkFunction.Context): Unit = sinkFunction.invoke(mapper.apply(value), ctx)
    }
  }

  private def prepareErrorTrackingFileSink(outputPath: String, schema: Schema): SinkFunction[GenericRecord] = {
    StreamingFileSink.forBulkFormat(new Path(outputPath), ParquetAvroWriters.forGenericRecord(schema))
      .withRollingPolicy(OnCheckpointRollingPolicy.build())
      .build()
  }
}
