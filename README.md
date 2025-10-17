# zeebe-kafka-exporter

[![Compatible with: Camunda Platform 8](https://img.shields.io/badge/Compatible%20with-Camunda%20Platform%208-0072Ce)](https://github.com/camunda-community-hub/community/blob/main/extension-lifecycle.md#compatiblilty)
[![Maven Central](https://maven-badges.sml.io/sonatype-central/io.zeebe.kafka/zeebe-kafka-exporter/badge.svg)](https://maven-badges.sml.io/sonatype-central/io.zeebe.kafka/zeebe-kafka-exporter)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Export records from [Zeebe](https://github.com/camunda-cloud/zeebe) to [Apache Kafka](https://kafka.apache.org/). Kafka is a distributed streaming platform used as a transport layer.

The records are transformed into [Protobuf](https://github.com/camunda-community-hub/zeebe-exporter-protobuf) or JSON and published to Kafka topics. Each Zeebe record type gets its own topic, allowing for efficient message routing and processing.

Multiple applications can consume from Kafka topics using consumer groups. Scaling of consumers per consumer group can be achieved by using multiple consumer instances. Separation of concerns is possible by consuming from different topics.

## Why Kafka?

Apache Kafka ([https://kafka.apache.org/](https://kafka.apache.org/)) is a distributed streaming platform that provides high-throughput, fault-tolerant messaging. It offers excellent scalability, durability, and performance characteristics. Kafka's topic-based architecture allows for efficient message routing and processing, making it ideal for event-driven architectures.

The Kafka producer client provides robust connectivity features out of the box, including automatic retries, batching, and compression, making the exporter reliable and performant.

If you need a robust, scalable messaging solution for your Zeebe events, this Kafka exporter is the right choice.

## Prerequisites

- Camunda 8.7+
- For Camunda versions 8.5 to 8.6 please use version 1.1.0 of this exporter
- For Camunda versions 8.2 to 8.4 please use version 0.9.11 of this exporter

## Usage

### Java Application

Add the Maven dependency to your `pom.xml`

```xml
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-clients</artifactId>
    <version>3.7.0</version>
</dependency>
```

Connect to Kafka and consume messages

```java
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

Properties props = new Properties();
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
props.put(ConsumerConfig.GROUP_ID_CONFIG, "zeebe-consumer-group");
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Arrays.asList("zeebe-DEPLOYMENT", "zeebe-JOB"));

while (true) {
    ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
    for (ConsumerRecord<String, byte[]> record : records) {
        // Process the record
        System.out.printf("Topic: %s, Key: %s, Value: %s%n",
            record.topic(), record.key(), new String(record.value()));
    }
}
```

**Hint**: It is strongly recommended to set a consumer group name for proper message distribution and fault tolerance.

## Install

### Docker

The easiest way to get started is using Docker Compose:

```yaml
version: "2"

networks:
  zeebe_network:
    driver: bridge

services:
  zeebe:
    container_name: zeebe_broker
    image: camunda/zeebe:8.7.14
    environment:
      - ZEEBE_LOG_LEVEL=debug
      - ZEEBE_KAFKA_BOOTSTRAP_SERVERS=kafka:9092
    ports:
      - "26500:26500"
      - "9600:9600"
    volumes:
      - ./zeebe-kafka-exporter-jar-with-dependencies.jar:/usr/local/zeebe/exporters/zeebe-kafka-exporter-jar-with-dependencies.jar
      - ./application.yaml:/usr/local/zeebe/config/application.yaml
    networks:
      - zeebe_network
    depends_on:
      - kafka

  zookeeper:
    image: confluentinc/cp-zookeeper:7.4.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    networks:
      - zeebe_network

  kafka:
    image: confluentinc/cp-kafka:7.4.0
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: true
    networks:
      - zeebe_network
```

### Manual

1. Download the latest [exporter JAR](https://github.com/janrohwer/zeebe-kafka-exporter/releases) (_zeebe-kafka-exporter-2.1.1-SNAPSHOT-jar-with-dependencies.jar_)

1. Copy the exporter JAR into the broker folder `~/zeebe-broker-%{VERSION}/exporters`.

   ```
   cp exporter/target/zeebe-kafka-exporter-2.1.1-SNAPSHOT-jar-with-dependencies.jar ~/zeebe-broker-%{VERSION}/exporters/
   ```

1. Add the exporter to the broker configuration `~/zeebe-broker-%{VERSION}/config/application.yaml`:

   ```
   zeebe:
     broker:
       exporters:
         kafka:
           className: io.zeebe.kafka.exporter.KafkaExporter
           jarPath: exporters/zeebe-kafka-exporter-2.1.1-SNAPSHOT-jar-with-dependencies.jar
   ```

1. Set the environment variable `ZEEBE_KAFKA_BOOTSTRAP_SERVERS` to your Kafka bootstrap servers.

1. Start the broker.

### Configuration

In the Zeebe configuration, you can furthermore change

- the value and record types which are exported
- the intents which are exported (with fuzzy or strict filtering)
- the name resulting in a topic prefix
- Kafka producer configuration (acks, retries, batch size, compression, etc.)
- batch size and cycle
- the record serialization format

Default values:

```yaml
zeebe:
  broker:
    exporters:
      kafka:
        className: io.zeebe.kafka.exporter.KafkaExporter
        jarPath: exporters/zeebe-kafka-exporter-jar-with-dependencies.jar
        args:
          # comma separated list of io.zeebe.protocol.record.RecordType to export or empty to export all types
          enabledRecordTypes: ""

          # comma separated list of io.zeebe.protocol.record.ValueType to export or empty to export all types
          enabledValueTypes: ""

          # comma separated list of io.zeebe.protocol.record.intent.Intent to export or empty to export all intents
          enabledIntents: ""

          # Kafka topic prefix
          name: "zeebe"

          # record serialization format: [protobuf|json]
          format: "json"

          # Kafka bootstrap servers
          bootstrapServers: "localhost:9092"

          # Kafka producer configuration
          acks: "all"
          retries: 3
          kafkaBatchSize: 16384
          lingerMs: 5
          bufferMemory: 33554432
          compressionType: "snappy"
```

The values can be overridden by environment variables with the same name and a `ZEEBE_KAFKA_` prefix (e.g. `ZEEBE_KAFKA_BOOTSTRAP_SERVERS`, `ZEEBE_KAFKA_ACKS`, ...).

Especially when it comes to `ZEEBE_KAFKA_BOOTSTRAP_SERVERS` it is recommended to define it as environment variable
in order to be able to change the Kafka connection without touching the broker configuration.

#### Intent Filtering

The exporter supports fine-grained intent filtering to control which specific intents are exported. This provides two filtering modes:

| **Parameter**                 | **Description**                                                                                           |
| ----------------------------- | --------------------------------------------------------------------------------------------------------- |
| `ZEEBE_KAFKA_ENABLED_INTENTS` | Controls which intents are exported. Supports both fuzzy and strict filtering modes (see examples below). |

##### - Fuzzy Intent Filtering (Simple List)

Export specific intents across all intent classes:

```
ZEEBE_KAFKA_ENABLED_INTENTS="CREATED,UPDATED,COMPLETED"
```

This will export `CREATED`, `UPDATED`, and `COMPLETED` intents from all intent classes (JobIntent, ProcessIntent, etc.).

##### - Strict Intent Filtering (Class-Specific)

Export specific intents only from designated intent classes:

```
ZEEBE_KAFKA_ENABLED_INTENTS="JobIntent=CREATED,UPDATED,COMPLETED;DeploymentIntent=CREATED;ProcessIntent=ACTIVATED,COMPLETED"
```

This provides precise control by specifying which intents to export from each intent class:

- `JobIntent`: Only `CREATED`, `UPDATED`, and `COMPLETED`
- `DeploymentIntent`: Only `CREATED`
- `ProcessIntent`: Only `ACTIVATED` and `COMPLETED`

##### - Default Behavior

If `ZEEBE_KAFKA_ENABLED_INTENTS` is not set or empty, all intents are exported.

The exporter automatically detects the filtering mode based on the configuration format:

- Contains `=` → Strict filtering mode
- No `=` → Fuzzy filtering mode

#### Tuning exporter performance

The exporter provides several configuration parameters to tune its performance:

| **Parameter**                    | **Description**                                                                                                                                         |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ZEEBE_KAFKA_BATCH_SIZE`         | Batch size for flushing commands when sending data. Default is `250`. More precisely the maximum number of events which will be flushed simultaneously. |
| `ZEEBE_KAFKA_BATCH_CYCLE_MILLIS` | Batch cycle in milliseconds for sending data. Default is `500`. Even if the batch size has not been reached events will be sent after this time.        |

The exporter queues records and sends them every `ZEEBE_KAFKA_BATCH_CYCLE_MILLIS`. Sending data then uses Kafka's built-in batching capabilities using `ZEEBE_KAFKA_BATCH_SIZE` as maximum thus increasing the throughput.

### Obtaining Metrics

The Kafka Exporter provides metrics via Spring Boot Actuator similar to the OpenSearch Exporter:

- `zeebe.kafka.exporter.bulk.memory.size` - Exporter bulk memory size
- `zeebe.kafka.exporter.bulk.size` - Exporter bulk size
- `zeebe.kafka.exporter.flush.duration.seconds` - Flush duration of bulk exporters in seconds
- `zeebe.kafka.exporter.failed.flush` - Number of failed flush operations

Metrics are recorded each batch cycle and are related to the sum of all events exported during such a single cycle.

- For more about Zeebe metrics go to [Camunda 8 Docs | Operational guides > Monitoring > Metrics](https://docs.camunda.io/docs/self-managed/operational-guides/monitoring/metrics/)

## Build it from Source

The exporter can be built with Maven

```bash
mvn clean package
```

This will create the JAR file in the `exporter/target/` directory.

## License

Licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file for details.
