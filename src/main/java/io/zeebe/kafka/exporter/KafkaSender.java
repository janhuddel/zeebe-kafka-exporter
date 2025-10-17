package io.zeebe.kafka.exporter;

import io.camunda.zeebe.exporter.api.context.Controller;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;

public class KafkaSender {

  private final Logger logger;
  private final KafkaMetrics kafkaMetrics;
  private final Controller controller;
  private final KafkaProducer<String, byte[]> kafkaProducer;

  private final AtomicBoolean kafkaConnected = new AtomicBoolean(true);

  private final AtomicBoolean metricsBulkRecorded = new AtomicBoolean(false);
  private final AtomicLong lastRecordedBulk = new AtomicLong(Long.MAX_VALUE);
  // best practice default scrape interval for metrics is 60 seconds, hence wait
  // 60s before
  // resetting to 0
  private static final long RESET_METRICS_AFTER_MILLIS = 60000L;

  public KafkaSender(
      ExporterConfiguration configuration,
      Controller controller,
      KafkaProducer<String, byte[]> kafkaProducer,
      MeterRegistry meterRegistry,
      Logger logger) {
    this.controller = controller;
    this.kafkaProducer = kafkaProducer;
    this.logger = logger;
    this.kafkaMetrics = new KafkaMetrics(meterRegistry);
  }

  void sendFrom(EventQueue eventQueue) {
    if (!kafkaConnected.get() || eventQueue.isEmpty()) {
      if (metricsBulkRecorded.get() && isMetricsWatchStopped()) {
        // set back bulk metric values to 0 once because there is nothing to send
        kafkaMetrics.recordBulkSize(0);
        kafkaMetrics.recordBulkMemorySize(0);
        metricsBulkRecorded.set(false);
      }
      return;
    }

    int recordBulkSize = 0;
    int recordBulkMemorySize = 0;
    Long positionOfLastRecordInBatch = -1L;

    try (final var ignored = kafkaMetrics.measureFlushDuration()) {
      ImmutablePair<Long, KafkaEvent> nextEvent = eventQueue.getNextEvent();
      while (nextEvent != null) {
        var eventValue = nextEvent.getValue();

        byte[] valueBytes = convertToBytes(eventValue.value);
        if (valueBytes == null) {
          nextEvent = eventQueue.getNextEvent();
          continue;
        }

        ProducerRecord<String, byte[]> record =
            new ProducerRecord<>(eventValue.topic, eventValue.key, valueBytes);

        // Send asynchronously with callback - let Kafka handle batching internally
        final Long recordPosition = nextEvent.getKey();
        kafkaProducer.send(
            record,
            (metadata, exception) -> {
              if (exception == null) {
                controller.updateLastExportedRecordPosition(recordPosition);
                logger.debug(
                    "Successfully sent record to topic {} partition {}",
                    metadata.topic(),
                    metadata.partition());
              } else {
                logger.error("Failed to send record to Kafka", exception);
                kafkaMetrics.recordFailedFlush();
              }
            });

        positionOfLastRecordInBatch = nextEvent.getKey();
        recordBulkSize++;
        recordBulkMemorySize += eventValue.memorySize;
        nextEvent = eventQueue.getNextEvent();
      }

      kafkaMetrics.recordBulkSize(recordBulkSize);
      kafkaMetrics.recordBulkMemorySize(recordBulkMemorySize);
      metricsBulkRecorded.set(true);
      startMetricsStopWatch();

    } catch (Exception ex) {
      kafkaMetrics.recordFailedFlush();
      logger.error("Error when sending events to Kafka", ex);
    }
  }

  private byte[] convertToBytes(Object value) {
    if (value instanceof byte[]) {
      return (byte[]) value;
    } else if (value instanceof String) {
      return ((String) value).getBytes();
    } else {
      logger.warn("Unknown value type: {}", value.getClass());
      return null;
    }
  }

  private void startMetricsStopWatch() {
    lastRecordedBulk.set(System.currentTimeMillis());
  }

  private boolean isMetricsWatchStopped() {
    return System.currentTimeMillis() - lastRecordedBulk.get() > RESET_METRICS_AFTER_MILLIS;
  }
}
