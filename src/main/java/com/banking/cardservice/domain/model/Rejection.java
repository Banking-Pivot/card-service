package com.banking.cardservice.domain.model;

import java.util.Objects;

/**
 * Why Account Service refused an account.
 *
 * @param code error code of Account Service, the same its own API answers
 * @param message its explanation
 */
public record Rejection(String code, String message) {

  /** Both the code and the message are required. */
  public Rejection {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
  }
}
