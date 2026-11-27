package com.banking.cardservice.infrastructure.adapter.in.kafka;

import com.banking.cardservice.application.model.AccountVerification;
import com.banking.cardservice.application.port.in.ApplyAccountVerificationUseCase;
import com.banking.cardservice.infrastructure.config.CardProperties;
import com.banking.cardservice.infrastructure.correlation.CorrelationId;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives the answers of Account Service about the accounts cards want to link and applies them.
 *
 * <p>The method waits until the answer is stored before it returns. It runs on the thread of the
 * Kafka consumer, not on one that serves requests, so waiting there is safe and it is what keeps
 * the answers in order: the position in the topic only moves forward once an answer is fully
 * handled. An answer that fails is retried by the container (see MessagingConfiguration).
 *
 * <p>An answer that cannot be read (not JSON, unknown type, missing fields) is logged and skipped:
 * retrying would never fix it.
 */
@Component
public class AccountReplyListener {

  private static final Logger LOG = LoggerFactory.getLogger(AccountReplyListener.class);

  private final ApplyAccountVerificationUseCase applyVerification;
  private final JsonMapper json;
  private final CardProperties.Messaging messaging;

  /** Creates the listener with the use case, the JSON mapper and the settings of the topics. */
  public AccountReplyListener(
      ApplyAccountVerificationUseCase applyVerification,
      JsonMapper json,
      CardProperties properties) {
    this.applyVerification = applyVerification;
    this.json = json;
    this.messaging = properties.messaging();
  }

  /** Topic the listener reads, taken from the settings. */
  public String getRepliesTopic() {
    return messaging.repliesTopic();
  }

  /**
   * Applies one answer. The correlation identifier of its header is in every log line written while
   * it is handled.
   *
   * @param message the record read from the replies topic
   */
  @KafkaListener(topics = "#{__listener.repliesTopic}")
  public void onReply(ConsumerRecord<String, String> message) {
    String correlationId = KafkaMessages.correlationId(message);
    try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationId.KEY, correlationId)) {
      read(message)
          .ifPresent(
              verification ->
                  applyVerification
                      .execute(verification)
                      .contextWrite(context -> context.put(CorrelationId.KEY, correlationId))
                      .block(messaging.handlingTimeout()));
    }
  }

  private Optional<AccountVerification> read(ConsumerRecord<String, String> message) {
    try {
      return Optional.of(
          json.readValue(message.value(), AccountReplyMessage.class).toVerification());
    } catch (JacksonException | IllegalArgumentException | NullPointerException unreadable) {
      // The content is not logged: it may be anything.
      LOG.warn(
          "Reply skipped, it cannot be read. topic={}, partition={}, offset={}, reason={}",
          message.topic(),
          message.partition(),
          message.offset(),
          unreadable.getClass().getSimpleName());
      return Optional.empty();
    }
  }
}
