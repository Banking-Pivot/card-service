package com.banking.cardservice.application.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Request to issue a debit card.
 *
 * @param customerId customer who will hold the card
 * @param mainAccountId account the card charges first
 * @param correlationId correlation identifier of the request
 */
public record IssueCardCommand(UUID customerId, UUID mainAccountId, String correlationId) {

  /** All three values are required. */
  public IssueCardCommand {
    Objects.requireNonNull(customerId, "customerId");
    Objects.requireNonNull(mainAccountId, "mainAccountId");
    Objects.requireNonNull(correlationId, "correlationId");
  }
}
