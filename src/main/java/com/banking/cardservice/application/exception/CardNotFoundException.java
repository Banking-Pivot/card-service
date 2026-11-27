package com.banking.cardservice.application.exception;

import java.util.UUID;

/** No card has that identifier. */
public class CardNotFoundException extends RuntimeException {

  public CardNotFoundException(UUID cardId) {
    super("Card " + cardId + " does not exist.");
  }
}
