package com.banking.cardservice.infrastructure.adapter.in.kafka;

import com.banking.cardservice.infrastructure.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** What both listeners read from a Kafka record besides its value. */
final class KafkaMessages {

  private KafkaMessages() {}

  /** The correlation identifier to work with: the one in the header, or a new one. */
  static String correlationId(ConsumerRecord<String, String> message) {
    Header header = message.headers().lastHeader(CorrelationId.HEADER);
    return CorrelationId.accept(
        header == null || header.value() == null
            ? null
            : new String(header.value(), StandardCharsets.UTF_8));
  }
}
