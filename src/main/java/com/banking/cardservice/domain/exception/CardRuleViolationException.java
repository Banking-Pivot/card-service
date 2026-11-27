package com.banking.cardservice.domain.exception;

import java.util.Objects;

/** A change a card does not allow in its current state. */
public class CardRuleViolationException extends RuntimeException {

  private final RuleViolation violation;

  /** Creates the exception with the broken rule and a message that can be shown to the caller. */
  public CardRuleViolationException(RuleViolation violation, String message) {
    super(message);
    this.violation = Objects.requireNonNull(violation);
  }

  public RuleViolation violation() {
    return violation;
  }
}
