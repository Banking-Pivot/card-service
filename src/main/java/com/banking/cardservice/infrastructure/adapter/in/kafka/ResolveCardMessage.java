package com.banking.cardservice.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * The RESOLVE_CARD command as it travels in Kafka. Fields this service does not know are ignored.
 *
 * @param type must be {@link #RESOLVE_CARD}
 * @param transactionId what the question belongs to; the answer carries it back and is keyed by it
 * @param cardId the card asked about
 * @param replyTo topic the answer goes to, or null for the default one
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ResolveCardMessage(String type, UUID transactionId, UUID cardId, String replyTo) {

  static final String RESOLVE_CARD = "RESOLVE_CARD";

  /** Whether the type is the known one and the two identifiers are present. */
  boolean isComplete() {
    return RESOLVE_CARD.equals(type) && transactionId != null && cardId != null;
  }
}
