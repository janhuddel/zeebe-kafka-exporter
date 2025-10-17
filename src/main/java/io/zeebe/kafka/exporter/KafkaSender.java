package io.zeebe.kafka.exporter;

import io.camunda.zeebe.exporter.api.context.Controller;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;

public class KafkaSender {

  private final Logger logger;
  private final KafkaMetrics kafkaMetrics;
  private final Controller controller;
  private final KafkaProducer<String, byte[]> kafkaProducer;

  private final AtomicBoolean kafkaConnected = new AtomicBoolean(true);

  private final int batchSize;

  private final List<ImmutablePair<Long, KafkaEvent>> deQueue = new ArrayList<>();

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
    this.batchSize = configuration.getBatchSize();
    this.controller = controller;
    this.kafkaProducer = kafkaProducer;
    this.logger = logger;
    this.kafkaMetrics = new KafkaMetrics(meterRegistry);
  }

  void sendFrom(EventQueue eventQueue) {
    if (!kafkaConnected.get() || !sendDeQueue() || eventQueue.isEmpty()) {
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
    try (final var ignored = kafkaMetrics.measureFlushDuration()) {
      Long positionOfLastRecordInBatch = -1L;
      List<Future<RecordMetadata>> futures = new ArrayList<>();
      ImmutablePair<Long, KafkaEvent> nextEvent = eventQueue.getNextEvent();
      while (nextEvent != null) {
        for (int i = 0; i < batchSize; i++) {
          deQueue.add(nextEvent);
          var eventValue = nextEvent.getValue();

          byte[] valueBytes;
          if (eventValue.value instanceof byte[]) {
            valueBytes = (byte[]) eventValue.value;
          } else if (eventValue.value instanceof String) {
            valueBytes = ((String) eventValue.value).getBytes();
          } else {
            logger.warn("Unknown value type: {}", eventValue.value.getClass());
            continue;
          }

          ProducerRecord<String, byte[]> record =
              new ProducerRecord<>(eventValue.topic, eventValue.key, valueBytes);

          futures.add(kafkaProducer.send(record));
          positionOfLastRecordInBatch = nextEvent.getKey();
          nextEvent = eventQueue.getNextEvent();
          recordBulkSize++;
          recordBulkMemorySize += eventValue.memorySize;
          if (nextEvent == null) {
            break;
          }
        }
        if (futures.size() > 0) {
          // Wait for all futures to complete
          boolean allSuccessful = true;
          for (var future : futures) {
            try {
              future.get(); // This will block until the record is sent
            } catch (Exception ex) {
              logger.error("Failed to send record to Kafka", ex);
              allSuccessful = false;
              break;
            }
          }

          if (allSuccessful) {
            controller.updateLastExportedRecordPosition(positionOfLastRecordInBatch);
            deQueue.clear();
            logger.debug("Exported {} events to Kafka", futures.size());
            futures.clear();
          } else {
            break;
          }
        }
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

  private boolean sendDeQueue() {
    if (deQueue.isEmpty()) {
      return true;
    }
    int recordBulkSize = 0;
    int recordBulkMemorySize = 0;
    try (final var ignored = kafkaMetrics.measureFlushDuration()) {
      Long positionOfLastRecordInBatch = -1L;
      List<Future<RecordMetadata>> futures = new ArrayList<>();
      for (var nextEvent : deQueue) {
        var eventValue = nextEvent.getValue();

        byte[] valueBytes;
        if (eventValue.value instanceof byte[]) {
          valueBytes = (byte[]) eventValue.value;
        } else if (eventValue.value instanceof String) {
          valueBytes = ((String) eventValue.value).getBytes();
        } else {
          logger.warn("Unknown value type: {}", eventValue.value.getClass());
          continue;
        }

        ProducerRecord<String, byte[]> record =
            new ProducerRecord<>(eventValue.topic, eventValue.key, valueBytes);

        futures.add(kafkaProducer.send(record));
        positionOfLastRecordInBatch = nextEvent.getKey();
        recordBulkSize++;
        recordBulkMemorySize += eventValue.memorySize;
      }

      // Wait for all futures to complete
      boolean allSuccessful = true;
      for (var future : futures) {
        try {
          future.get(); // This will block until the record is sent
        } catch (Exception ex) {
          logger.error("Failed to send dequeued record to Kafka", ex);
          allSuccessful = false;
          break;
        }
      }

      if (allSuccessful) {
        controller.updateLastExportedRecordPosition(positionOfLastRecordInBatch);
        logger.debug("Exported {} dequeued events to Kafka", futures.size());
        deQueue.clear();
        kafkaMetrics.recordBulkSize(recordBulkSize);
        kafkaMetrics.recordBulkMemorySize(recordBulkMemorySize);
        metricsBulkRecorded.set(true);
        startMetricsStopWatch();
        return true;
      }
    } catch (Exception ex) {
      kafkaMetrics.recordFailedFlush();
      logger.error("Error when sending dequeued events to Kafka", ex);
    }
    return false;
  }

  private void startMetricsStopWatch() {
    lastRecordedBulk.set(System.currentTimeMillis());
  }

  private boolean isMetricsWatchStopped() {
    return System.currentTimeMillis() - lastRecordedBulk.get() > RESET_METRICS_AFTER_MILLIS;
  }
}
