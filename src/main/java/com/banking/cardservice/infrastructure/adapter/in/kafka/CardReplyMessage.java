package com.banking.cardservice.infrastructure.adapter.in.kafka;

import com.banking.cardservice.application.model.CardResolution;
import com.banking.cardservice.domain.model.vo.HolderDocument;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * The answer to RESOLVE_CARD as it travels in Kafka: one JSON object whose {@code type} says what
 * happened. Fields that do not apply to a type are left out.
 *
 * @param transactionId the one the command carried
 * @param customerId CARD_RESOLVED only: customer who holds the card
 * @param mainAccountId CARD_RESOLVED only: account the card charges first
 * @param accountIds CARD_RESOLVED only: the linked accounts, main first, then in linking order
 * @param holderDocumentType CARD_RESOLVED only: type of the identity document of the customer, left
 *     out when the card does not know it
 * @param holderDocumentNumber CARD_RESOLVED only: number of that document, left out with the type
 * @param code CARD_RESOLUTION_REJECTED only: {@code CARD_NOT_FOUND} or {@code CARD_NOT_ACTIVE}
 * @param message CARD_RESOLUTION_REJECTED only: explanation
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record CardReplyMessage(
    String type,
    UUID transactionId,
    UUID cardId,
    UUID customerId,
    UUID mainAccountId,
    List<UUID> accountIds,
    String holderDocumentType,
    String holderDocumentNumber,
    String code,
    String message) {

  static final String CARD_RESOLVED = "CARD_RESOLVED";
  static final String CARD_RESOLUTION_REJECTED = "CARD_RESOLUTION_REJECTED";

  static CardReplyMessage of(UUID transactionId, CardResolution resolution) {
    if (resolution instanceof CardResolution.Resolved resolved) {
      HolderDocument holder = resolved.holderDocument();
      return new CardReplyMessage(
          CARD_RESOLVED,
          transactionId,
          resolved.cardId(),
          resolved.customerId(),
          resolved.mainAccountId(),
          resolved.accountIds(),
          holder == null ? null : holder.type(),
          holder == null ? null : holder.number(),
          null,
          null);
    }
    CardResolution.Refused refused = (CardResolution.Refused) resolution;
    return new CardReplyMessage(
        CARD_RESOLUTION_REJECTED,
        transactionId,
        refused.cardId(),
        null,
        null,
        null,
        null,
        null,
        refused.code(),
        refused.message());
  }
}
