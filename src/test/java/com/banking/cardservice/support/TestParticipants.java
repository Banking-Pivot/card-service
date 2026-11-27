package com.banking.cardservice.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plays the other services on a real Kafka: Account Service, which reads VERIFY_ACCOUNT and answers
 * it, and Transaction Service and Yanki Service, which ask RESOLVE_CARD and read the answer on
 * their own topics.
 *
 * <p>Messages are looked up by their transactionId, so what other tests left in the topics does not
 * get in the way.
 */
public final class TestParticipants {

  public static final String ACCOUNT_COMMANDS = "banking.account.commands";
  public static final String CARD_COMMANDS = "banking.card.commands";
  public static final String CARD_REPLIES = "banking.card.replies";
  public static final String TRANSACTION_REPLIES = "banking.transaction.replies";
  public static final String YANKI_REPLIES = "banking.yanki.replies";

  private static final Duration PATIENCE = Duration.ofSeconds(30);

  private final KafkaConsumer<String, String> consumer;
  private final KafkaProducer<String, String> producer;
  private final List<ReceivedMessage> seen = new ArrayList<>();
  private final JsonMapper json = JsonMapper.builder().build();

  private TestParticipants(String bootstrapServers) {
    Map<String, Object> reading = new LinkedHashMap<>();
    reading.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    reading.put(ConsumerConfig.GROUP_ID_CONFIG, "test-participants-" + UUID.randomUUID());
    reading.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    reading.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
    reading.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    reading.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumer = new KafkaConsumer<>(reading);
    consumer.subscribe(List.of(ACCOUNT_COMMANDS, TRANSACTION_REPLIES, YANKI_REPLIES));

    Map<String, Object> writing = new LinkedHashMap<>();
    writing.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    writing.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    writing.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producer = new KafkaProducer<>(writing);
  }

  /** Connects once the service created the topics. */
  public static TestParticipants connect(String bootstrapServers) {
    return new TestParticipants(bootstrapServers);
  }

  /** The first message of that type with that transactionId; fails if none arrives. */
  public ReceivedMessage awaitMessage(String type, UUID transactionId) {
    return awaitMessage(type, transactionId, 1);
  }

  /** The n-th message of that type with that transactionId, counting from 1. */
  public ReceivedMessage awaitMessage(String type, UUID transactionId, int occurrence) {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    while (true) {
      List<ReceivedMessage> matches = messages(type, transactionId);
      if (matches.size() >= occurrence) {
        return matches.get(occurrence - 1);
      }
      if (System.nanoTime() > deadline) {
        throw new AssertionError(
            "Expected message "
                + type
                + " #"
                + occurrence
                + " for "
                + transactionId
                + " but saw "
                + matches.size());
      }
      poll(Duration.ofMillis(200));
    }
  }

  /** How many messages of that type the transactionId got, after listening a while longer. */
  public int countMessages(String type, UUID transactionId, Duration listenFor) {
    listen(listenFor);
    return messages(type, transactionId).size();
  }

  /** How many messages of any type carry that transactionId, after listening a while longer. */
  public int countMessages(UUID transactionId, Duration listenFor) {
    listen(listenFor);
    return (int)
        seen.stream()
            .filter(message -> transactionId.toString().equals(message.field("transactionId")))
            .count();
  }

  // ----- answers of Account Service

  /** The account exists and the customer is one of its holders. */
  public void accountVerified(UUID linkId, UUID accountId, UUID customerId, String currency) {
    Map<String, Object> body = message("ACCOUNT_VERIFIED", linkId);
    body.put("accountId", accountId.toString());
    body.put("customerId", customerId.toString());
    body.put("currency", currency);
    // A field Card Service does not know: it must not get in the way.
    body.put("accountType", "SAVINGS");
    send(CARD_REPLIES, linkId.toString(), json.writeValueAsString(body), "reply-" + linkId);
  }

  /** The same answer with the identity document of the customer, as Account Service sends it. */
  public void accountVerified(
      UUID linkId,
      UUID accountId,
      UUID customerId,
      String currency,
      String documentType,
      String documentNumber) {
    Map<String, Object> body = message("ACCOUNT_VERIFIED", linkId);
    body.put("accountId", accountId.toString());
    body.put("customerId", customerId.toString());
    body.put("currency", currency);
    body.put("documentType", documentType);
    body.put("documentNumber", documentNumber);
    send(CARD_REPLIES, linkId.toString(), json.writeValueAsString(body), "reply-" + linkId);
  }

  /** The account does not exist or the customer is not one of its holders. */
  public void accountVerificationRejected(
      UUID linkId, UUID accountId, String code, String message) {
    Map<String, Object> body = message("ACCOUNT_VERIFICATION_REJECTED", linkId);
    body.put("accountId", accountId.toString());
    body.put("code", code);
    body.put("message", message);
    send(CARD_REPLIES, linkId.toString(), json.writeValueAsString(body), "reply-" + linkId);
  }

  // ----- questions of Transaction Service and Yanki Service

  /** Asks which accounts the card uses; {@code replyTo} null leaves the field out. */
  public void resolveCard(UUID transactionId, UUID cardId, String replyTo) {
    Map<String, Object> body = message("RESOLVE_CARD", transactionId);
    body.put("cardId", cardId.toString());
    if (replyTo != null) {
      body.put("replyTo", replyTo);
    }
    // A field Card Service does not know.
    body.put("amount", 25);
    send(
        CARD_COMMANDS,
        cardId.toString(),
        json.writeValueAsString(body),
        "resolve-" + transactionId);
  }

  /** Puts any text in a topic, to send what a well-behaved service never would. */
  public void send(String topic, String key, String value, String correlationId) {
    ProducerRecord<String, String> message = new ProducerRecord<>(topic, key, value);
    if (correlationId != null) {
      message.headers().add("X-Correlation-ID", correlationId.getBytes(StandardCharsets.UTF_8));
    }
    try {
      producer.send(message).get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    } catch (ExecutionException failed) {
      throw new IllegalStateException(failed.getCause());
    }
  }

  private static Map<String, Object> message(String type, UUID transactionId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type);
    body.put("transactionId", transactionId.toString());
    return body;
  }

  private void listen(Duration listenFor) {
    long deadline = System.nanoTime() + listenFor.toNanos();
    do {
      poll(Duration.ofMillis(200));
    } while (System.nanoTime() < deadline);
  }

  private List<ReceivedMessage> messages(String type, UUID transactionId) {
    return seen.stream()
        .filter(message -> type.equals(message.field("type")))
        .filter(message -> transactionId.toString().equals(message.field("transactionId")))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private void poll(Duration wait) {
    for (ConsumerRecord<String, String> message : consumer.poll(wait)) {
      Header header = message.headers().lastHeader("X-Correlation-ID");
      seen.add(
          new ReceivedMessage(
              message.topic(),
              message.key(),
              message.value(),
              (Map<String, Object>) json.readValue(message.value(), Map.class),
              header == null ? null : new String(header.value(), StandardCharsets.UTF_8)));
    }
  }

  /**
   * A message as it was read from Kafka.
   *
   * @param text the value exactly as sent
   * @param body the value parsed
   * @param correlationId value of the X-Correlation-ID header, or null
   */
  public record ReceivedMessage(
      String topic, String key, String text, Map<String, Object> body, String correlationId) {

    public String field(String name) {
      Object value = body.get(name);
      return value == null ? null : value.toString();
    }
  }
}
