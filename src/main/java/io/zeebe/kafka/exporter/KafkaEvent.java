package io.zeebe.kafka.exporter;

public class KafkaEvent {

  String topic;
  String key;
  Object value;
  int memorySize;

  public KafkaEvent(String topic, String key, Object value, int memorySize) {
    this.topic = topic;
    this.key = key;
    this.value = value;
    this.memorySize = memorySize;
  }
}
