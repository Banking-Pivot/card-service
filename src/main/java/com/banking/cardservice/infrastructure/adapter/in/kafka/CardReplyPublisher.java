package com.banking.cardservice.infrastructure.adapter.in.kafka;

import com.banking.cardservice.infrastructure.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends the answers to RESOLVE_CARD: JSON keyed by the transactionId of the command, so the answers
 * of one transaction keep their order, with the correlation identifier of the command in a header.
 */
@Component
class CardReplyPublisher {

  private final KafkaTemplate<String, String> kafka;
  private final JsonMapper json;

  CardReplyPublisher(KafkaTemplate<String, String> kafka, JsonMapper json) {
    this.kafka = kafka;
    this.json = json;
  }

  /**
   * Sends one answer to the topic the command asked for.
   *
   * @return completion once the broker has acknowledged the message; an error when it has not
   */
  Mono<Void> publish(String topic, CardReplyMessage reply, String correlationId) {
    // The producer can block while it looks for the broker, so it never runs on an event loop.
    return Mono.fromFuture(() -> kafka.send(toRecord(topic, reply, correlationId)))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  private ProducerRecord<String, String> toRecord(
      String topic, CardReplyMessage reply, String correlationId) {
    ProducerRecord<String, String> message =
        new ProducerRecord<>(
            topic, reply.transactionId().toString(), json.writeValueAsString(reply));
    message.headers().add(CorrelationId.HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
    return message;
  }
}
