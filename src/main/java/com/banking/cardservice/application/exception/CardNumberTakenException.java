package com.banking.cardservice.application.exception;

/** Another card already has the number that was generated for a new one. */
public class CardNumberTakenException extends RuntimeException {

  public CardNumberTakenException(Throwable cause) {
    super("The generated card number is already in use.", cause);
  }
}
