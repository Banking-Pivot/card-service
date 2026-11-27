package com.banking.cardservice.application.exception;

import java.util.UUID;

/** The card changed between the moment it was read and the moment the change was written. */
public class CardConcurrentModificationException extends RuntimeException {

  public CardConcurrentModificationException(UUID cardId) {
    super("Card " + cardId + " was changed by another operation.");
  }
}
