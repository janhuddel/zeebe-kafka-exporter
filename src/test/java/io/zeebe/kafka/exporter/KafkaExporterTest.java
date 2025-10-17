package io.zeebe.kafka.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.Test;

public class KafkaExporterTest {

  private static final BpmnModelInstance WORKFLOW =
      Bpmn.createExecutableProcess("process")
          .startEvent("start")
          .sequenceFlowId("to-task")
          .serviceTask("task", s -> s.zeebeJobType("test"))
          .sequenceFlowId("to-end")
          .endEvent("end")
          .done();

  @Test
  public void shouldCreateKafkaExporter() {
    // given
    KafkaExporter exporter = new KafkaExporter();

    // then
    assertThat(exporter).isNotNull();
  }

  @Test
  public void shouldCreateExporterConfiguration() {
    // given
    ExporterConfiguration config = new ExporterConfiguration();

    // then
    assertThat(config).isNotNull();
    assertThat(config.getFormat()).isEqualTo("protobuf");
    assertThat(config.getName()).isEqualTo("zeebe");
    assertThat(config.getTopicPrefix()).isEqualTo("zeebe-");
  }

  @Test
  public void shouldCreateKafkaEvent() {
    // given
    String topic = "zeebe-DEPLOYMENT";
    String key = "123";
    byte[] value = "test".getBytes();
    int memorySize = 4;

    // when
    KafkaEvent event = new KafkaEvent(topic, key, value, memorySize);

    // then
    assertThat(event.topic).isEqualTo(topic);
    assertThat(event.key).isEqualTo(key);
    assertThat(event.value).isEqualTo(value);
    assertThat(event.memorySize).isEqualTo(memorySize);
  }

  @Test
  public void shouldCreateEventQueue() {
    // given
    EventQueue queue = new EventQueue();

    // then
    assertThat(queue.isEmpty()).isTrue();
  }
}
