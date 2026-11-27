package com.banking.cardservice.domain.model.enums;

/**
 * Where a debit card is in its life.
 *
 * <ul>
 *   <li>{@link #PENDING_VERIFICATION}: issued, waiting for Account Service to confirm its first
 *       account.
 *   <li>{@link #ACTIVE}: it can pay.
 *   <li>{@link #REJECTED}: Account Service refused its first account. Final.
 *   <li>{@link #BLOCKED}: it cannot pay until it is unblocked.
 * </ul>
 */
public enum CardStatus {
  PENDING_VERIFICATION,
  ACTIVE,
  REJECTED,
  BLOCKED
}
