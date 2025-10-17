package io.zeebe.kafka.exporter;

import io.camunda.zeebe.exporter.api.Exporter;
import io.camunda.zeebe.exporter.api.context.Context;
import io.camunda.zeebe.exporter.api.context.Controller;
import io.camunda.zeebe.protocol.record.Record;
import io.micrometer.core.instrument.MeterRegistry;
import io.zeebe.exporter.proto.RecordTransformer;
import io.zeebe.exporter.proto.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;

public class KafkaExporter implements Exporter {

  private ExporterConfiguration config;
  private Logger logger;

  private KafkaProducer<String, byte[]> kafkaProducer;
  private Function<Record<?>, TransformedRecord> recordTransformer;

  private boolean useProtoBuf = false;

  private String topicPrefix;

  private EventQueue eventQueue = new EventQueue();

  private KafkaSender kafkaSender;

  private Controller controller;

  private MeterRegistry meterRegistry;

  // The ExecutorService allows to schedule a regular task independent of the
  // actual load
  // which controller.scheduleCancellableTask sadly didn't do.
  private ScheduledExecutorService senderThread = Executors.newSingleThreadScheduledExecutor();

  // Startup handling in case of Kafka connection failure
  private ScheduledExecutorService startupThread;
  private boolean fullyLoggedStartupException = false;
  private List<Integer> reconnectIntervals = new ArrayList<>(List.of(2, 3, 3, 4, 4, 4, 5));

  @Override
  public void configure(Context context) {
    logger = context.getLogger();
    meterRegistry = context.getMeterRegistry();
    config = context.getConfiguration().instantiate(ExporterConfiguration.class);

    logger.info(
        "Starting Kafka exporter version {} with configuration: {}",
        this.getClass().getPackage().getImplementationVersion(),
        config);

    topicPrefix = config.getTopicPrefix();

    final RecordFilter filter = new RecordFilter(config);
    context.setFilter(filter);

    configureFormat();
  }

  private void configureFormat() {
    final String format = config.getFormat();
    if (format.equalsIgnoreCase("protobuf")) {
      recordTransformer = this::recordToProtobuf;
      useProtoBuf = true;
    } else if (format.equalsIgnoreCase("json")) {
      recordTransformer = this::recordToJson;

    } else {
      throw new IllegalArgumentException(
          String.format(
              "Expected the parameter 'format' to be one of 'protobuf' or 'json' but was '%s'",
              format));
    }
  }

  @Override
  public void open(Controller controller) {
    if (config.getBootstrapServers().isEmpty()) {
      logger.error(
          "Kafka configuration error: Missing bootstrap servers. Please check ZEEBE_KAFKA_BOOTSTRAP_SERVERS environment variable or configuration.");
      throw new IllegalStateException("Missing ZEEBE_KAFKA_BOOTSTRAP_SERVERS configuration.");
    }
    this.controller = controller;

    logger.info(
        "Initializing Kafka producer with configuration: bootstrapServers={}, batchCycleMillis={}",
        config.getBootstrapServers().get(),
        config.getBatchCycleMillis());

    connectToKafka();
  }

  private void connectToKafka() {
    boolean failure = false;
    // try to connect
    try {
      logger.debug(
          "Attempting to establish Kafka connection to {}", config.getBootstrapServers().get());

      kafkaProducer = createKafkaProducer();

      logger.info(
          "Successfully connected Kafka exporter to {} using {} format",
          config.getBootstrapServers().get(),
          useProtoBuf ? "protobuf" : "json");
    } catch (Exception ex) {
      if (!fullyLoggedStartupException) {
        logger.error(
            "Failure connecting Kafka exporter to " + config.getBootstrapServers().get(), ex);
        fullyLoggedStartupException = true;
      } else {
        logger.warn(
            "Failure connecting Kafka exporter to {}: {}",
            config.getBootstrapServers().get(),
            ex.getMessage());
      }
      failure = true;
    }

    // upon successful connection initialize the sender
    if (kafkaSender == null && kafkaProducer != null) {
      logger.debug(
          "Initializing Kafka sender with cycle {} milliseconds", config.getBatchCycleMillis());
      kafkaSender = new KafkaSender(config, controller, kafkaProducer, meterRegistry, logger);
      senderThread.schedule(this::sendBatches, config.getBatchCycleMillis(), TimeUnit.MILLISECONDS);
    }

    // if initial connection has failed, try again later
    if (failure) {
      if (startupThread == null) {
        startupThread = Executors.newSingleThreadScheduledExecutor();
      }
      int delay =
          reconnectIntervals.size() > 1 ? reconnectIntervals.remove(0) : reconnectIntervals.get(0);
      logger.debug("Scheduling Kafka connection retry in {} seconds", delay);
      startupThread.schedule(this::connectToKafka, delay, TimeUnit.SECONDS);
    } else if (startupThread != null) {
      startupThread.shutdown();
      startupThread = null;
    }
  }

  private KafkaProducer<String, byte[]> createKafkaProducer() {
    var props = new java.util.Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers().get());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
    props.put(ProducerConfig.ACKS_CONFIG, config.getAcks());
    props.put(ProducerConfig.RETRIES_CONFIG, config.getRetries());
    props.put(ProducerConfig.BATCH_SIZE_CONFIG, config.getKafkaBatchSize());
    props.put(ProducerConfig.LINGER_MS_CONFIG, config.getLingerMs());
    props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, config.getBufferMemory());
    props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, config.getCompressionType());

    // Add any additional Kafka properties from configuration
    config.getAdditionalProperties().forEach(props::put);

    return new KafkaProducer<>(props);
  }

  @Override
  public void close() {
    senderThread.shutdown();
    if (kafkaProducer != null) {
      kafkaProducer.close();
      kafkaProducer = null;
    }
    if (startupThread != null) {
      startupThread.shutdown();
      startupThread = null;
    }
  }

  @Override
  public void export(Record<?> record) {
    final String topic = topicPrefix.concat(record.getValueType().name());
    final TransformedRecord transformedRecord = recordTransformer.apply(record);
    final KafkaEvent kafkaEvent =
        new KafkaEvent(
            topic,
            String.valueOf(record.getPosition()),
            transformedRecord.value,
            transformedRecord.memorySize);
    eventQueue.addEvent(new ImmutablePair<>(record.getPosition(), kafkaEvent));
  }

  private void sendBatches() {
    kafkaSender.sendFrom(eventQueue);
    senderThread.schedule(this::sendBatches, config.getBatchCycleMillis(), TimeUnit.MILLISECONDS);
  }

  private TransformedRecord recordToProtobuf(Record<?> record) {
    final Schema.Record dto = RecordTransformer.toGenericRecord(record);
    var value = dto.toByteArray();
    return new TransformedRecord(value, value.length);
  }

  private TransformedRecord recordToJson(Record<?> record) {
    var value = record.toJson();
    return new TransformedRecord(value, 2 * value.length() + 38);
  }

  private record TransformedRecord(Object value, int memorySize) {}
}
