package io.zeebe.kafka.exporter;

import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.commons.lang3.tuple.ImmutablePair;

public class EventQueue {

  private final ConcurrentLinkedQueue<ImmutablePair<Long, KafkaEvent>> queue =
      new ConcurrentLinkedQueue<>();

  public void addEvent(ImmutablePair<Long, KafkaEvent> event) {
    queue.add(event);
  }

  public ImmutablePair<Long, KafkaEvent> getNextEvent() {
    return queue.poll();
  }

  public boolean isEmpty() {
    return queue.isEmpty();
  }
}
