package com.banking.cardservice.application.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Request to link another account to a card.
 *
 * @param cardId the card
 * @param accountId the account to add
 * @param correlationId correlation identifier of the request
 */
public record LinkAccountCommand(UUID cardId, UUID accountId, String correlationId) {

  /** All three values are required. */
  public LinkAccountCommand {
    Objects.requireNonNull(cardId, "cardId");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(correlationId, "correlationId");
  }
}
