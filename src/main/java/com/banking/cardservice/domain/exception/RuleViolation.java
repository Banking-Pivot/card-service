package com.banking.cardservice.domain.exception;

/** Rules of a card that a request can break. The name is the error code of the API. */
public enum RuleViolation {
  /** The card is pending, rejected or blocked. */
  CARD_NOT_ACTIVE,
  /** The account is already linked to the card, or being checked. */
  ACCOUNT_ALREADY_LINKED,
  /** The account chosen as main is not LINKED to the card. */
  ACCOUNT_NOT_LINKED,
  /** The main account cannot be unlinked. */
  MAIN_ACCOUNT_REQUIRED,
  /** The customer already has as many cards as the bank allows. */
  CARD_LIMIT_REACHED
}
