package com.banking.cardservice.domain.exception;

import java.util.UUID;

/** The card has no link to the account that was asked to be removed. */
public class LinkedAccountNotFoundException extends RuntimeException {

  public LinkedAccountNotFoundException(UUID cardId, UUID accountId) {
    super("Card " + cardId + " has no link to account " + accountId + ".");
  }
}
