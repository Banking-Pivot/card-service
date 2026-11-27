package com.banking.cardservice.infrastructure.adapter.out.kafka;

import com.banking.cardservice.application.model.VerificationRequest;
import com.banking.cardservice.application.port.out.VerificationPublisherPort;
import com.banking.cardservice.infrastructure.config.CardProperties;
import com.banking.cardservice.infrastructure.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends VERIFY_ACCOUNT to Account Service as JSON text, asking it to answer on this service's
 * topic.
 *
 * <p>The message is keyed by the account, so Account Service handles it in order with everything
 * else asked of that account. The correlation identifier of the request goes in the {@code
 * X-Correlation-ID} header.
 *
 * <p>The Kafka client can block while it looks for the broker, so the send runs away from the
 * threads that serve requests, and it fails after the configured time instead of waiting.
 */
@Component
public class KafkaVerificationPublisher implements VerificationPublisherPort {

  private final KafkaTemplate<String, String> kafka;
  private final JsonMapper json;
  private final CardProperties.Messaging messaging;

  /** Creates the publisher with the Kafka client, the JSON mapper and the topic names. */
  public KafkaVerificationPublisher(
      KafkaTemplate<String, String> kafka, JsonMapper json, CardProperties properties) {
    this.kafka = kafka;
    this.json = json;
    this.messaging = properties.messaging();
  }

  @Override
  public Mono<Void> publish(VerificationRequest request) {
    return Mono.fromCallable(() -> toRecord(request))
        .flatMap(message -> Mono.fromFuture(() -> kafka.send(message)))
        .subscribeOn(Schedulers.boundedElastic())
        .timeout(messaging.publishTimeout())
        // The broker's confirmation arrives on the thread of the Kafka client; what follows (the
        // response of the request, the next query) must not run there.
        .publishOn(Schedulers.parallel())
        .then();
  }

  private ProducerRecord<String, String> toRecord(VerificationRequest request) {
    ProducerRecord<String, String> message =
        new ProducerRecord<>(
            messaging.accountCommandsTopic(),
            request.accountId().toString(),
            json.writeValueAsString(VerifyAccountMessage.of(request, messaging.repliesTopic())));
    message
        .headers()
        .add(CorrelationId.HEADER, request.correlationId().getBytes(StandardCharsets.UTF_8));
    return message;
  }
}
