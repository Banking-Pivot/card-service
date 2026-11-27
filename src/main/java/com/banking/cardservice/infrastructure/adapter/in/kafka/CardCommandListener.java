package com.banking.cardservice.infrastructure.adapter.in.kafka;

import com.banking.cardservice.application.port.in.ResolveCardUseCase;
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
 * Receives RESOLVE_CARD, the question "which accounts does this card use", and answers it on the
 * topic the command names: Transaction Service asks before it charges a card, Yanki Service before
 * it links a wallet to one.
 *
 * <ul>
 *   <li>A card that does not exist or is not active is answered with CARD_RESOLUTION_REJECTED and
 *       its code.
 *   <li>A command that fails because something did not work (the database, the answer itself) is
 *       not answered: the error leaves the listener and the container handles the command again a
 *       few times before it logs and skips it. Whoever asked asks again later; the question changes
 *       nothing here, so a repeat is simply answered again.
 *   <li>A message that cannot be answered (not JSON, unknown type, missing fields, a {@code
 *       replyTo} outside the allowed topics) is logged and skipped.
 * </ul>
 */
@Component
public class CardCommandListener {

  private static final Logger LOG = LoggerFactory.getLogger(CardCommandListener.class);

  private final ResolveCardUseCase resolveCard;
  private final CardReplyPublisher replies;
  private final JsonMapper json;
  private final CardProperties.Messaging messaging;

  /** Creates the listener with the use case it serves and the publisher of the answers. */
  public CardCommandListener(
      ResolveCardUseCase resolveCard,
      CardReplyPublisher replies,
      JsonMapper json,
      CardProperties properties) {
    this.resolveCard = resolveCard;
    this.replies = replies;
    this.json = json;
    this.messaging = properties.messaging();
  }

  /** Topic the commands arrive on, taken from the settings. */
  public String getCommandsTopic() {
    return messaging.commandsTopic();
  }

  /**
   * Handles one command and sends its answer.
   *
   * <p>The method waits for the answer to be out before it returns. It runs on the consumer thread
   * of Kafka, not on an event loop, so waiting here is safe and the offset is committed only once
   * the answer is out.
   *
   * @param message the record, with the correlation identifier in the {@code X-Correlation-ID}
   *     header
   */
  @KafkaListener(topics = "#{__listener.commandsTopic}")
  public void onCommand(ConsumerRecord<String, String> message) {
    String correlationId = KafkaMessages.correlationId(message);
    try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationId.KEY, correlationId)) {
      parse(message).ifPresent(command -> answer(command, correlationId));
    }
  }

  private void answer(ResolveCardMessage command, String correlationId) {
    String topic = command.replyTo() == null ? messaging.defaultReplyTopic() : command.replyTo();
    CardReplyMessage reply =
        resolveCard
            .execute(command.cardId())
            .map(resolution -> CardReplyMessage.of(command.transactionId(), resolution))
            .flatMap(answer -> replies.publish(topic, answer, correlationId).thenReturn(answer))
            .doOnError(
                failure ->
                    LOG.warn(
                        "RESOLVE_CARD could not be handled. transactionId={}, failureType={}",
                        command.transactionId(),
                        failure.getClass().getName()))
            .contextWrite(context -> context.put(CorrelationId.KEY, correlationId))
            .block(messaging.handlingTimeout());
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "RESOLVE_CARD -> {}. transactionId={}, cardId={}, replyTo={}, code={}",
          reply.type(),
          command.transactionId(),
          command.cardId(),
          topic,
          reply.code());
    }
  }

  // The position of the message only: its content can be anything.
  private Optional<ResolveCardMessage> parse(ConsumerRecord<String, String> message) {
    ResolveCardMessage command = read(message.value());
    if (command == null || !command.isComplete()) {
      if (LOG.isWarnEnabled()) {
        LOG.warn(
            "Skipping a message that is not a command: not JSON, unknown type or missing fields."
                + " topic={}, partition={}, offset={}",
            message.topic(),
            message.partition(),
            message.offset());
      }
      return Optional.empty();
    }
    if (command.replyTo() != null && !messaging.allowsReplyTo(command.replyTo())) {
      if (LOG.isWarnEnabled()) {
        LOG.warn(
            "Skipping a command that asks for its answer on a topic that is not allowed."
                + " topic={}, partition={}, offset={}, transactionId={}",
            message.topic(),
            message.partition(),
            message.offset(),
            command.transactionId());
      }
      return Optional.empty();
    }
    return Optional.of(command);
  }

  private ResolveCardMessage read(String value) {
    try {
      return value == null ? null : json.readValue(value, ResolveCardMessage.class);
    } catch (JacksonException unreadable) {
      return null;
    }
  }
}
