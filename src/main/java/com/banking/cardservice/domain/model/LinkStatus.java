package com.banking.cardservice.domain.model;

/**
 * State of the link between a card and a bank account: {@link #PENDING} while Account Service is
 * asked about the account, then {@link #LINKED} or {@link #REJECTED}.
 */
public enum LinkStatus {
  PENDING,
  LINKED,
  REJECTED
}
