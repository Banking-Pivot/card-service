package com.banking.cardservice.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A bank account linked to a card.
 *
 * @param id identifier of the link, also the transactionId of the question Account Service is asked
 *     about the account
 * @param accountId the bank account
 * @param position order the account was linked in, starting at 1
 * @param main whether it is the account the card charges first
 * @param status whether Account Service confirmed the account
 * @param currency currency of the account, known once it is confirmed
 * @param rejection why the account was refused, when it was
 * @param correlationId correlation identifier of the request that asked for the link
 * @param verificationSentAt when the question was last sent
 * @param verificationAttempts how many times the question was repeated
 * @param createdAt when the link was requested
 * @param updatedAt when the link last changed
 */
public record AccountLink(
    UUID id,
    UUID accountId,
    int position,
    boolean main,
    LinkStatus status,
    String currency,
    Rejection rejection,
    String correlationId,
    Instant verificationSentAt,
    int verificationAttempts,
    Instant createdAt,
    Instant updatedAt) {

  /** Everything is required except the currency and the rejection, which come with the answer. */
  public AccountLink {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(verificationSentAt, "verificationSentAt");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  static AccountLink pending(
      UUID id, UUID accountId, int position, boolean main, String correlationId, Instant now) {
    return new AccountLink(
        id,
        accountId,
        position,
        main,
        LinkStatus.PENDING,
        null,
        null,
        correlationId,
        now,
        0,
        now,
        now);
  }

  AccountLink verified(String accountCurrency, Instant now) {
    return new AccountLink(
        id,
        accountId,
        position,
        main,
        LinkStatus.LINKED,
        Objects.requireNonNull(accountCurrency, "currency"),
        null,
        correlationId,
        verificationSentAt,
        verificationAttempts,
        createdAt,
        now);
  }

  AccountLink rejected(Rejection reason, Instant now) {
    return new AccountLink(
        id,
        accountId,
        position,
        main,
        LinkStatus.REJECTED,
        null,
        Objects.requireNonNull(reason, "rejection"),
        correlationId,
        verificationSentAt,
        verificationAttempts,
        createdAt,
        now);
  }

  AccountLink withMain(boolean isMain, Instant now) {
    return new AccountLink(
        id,
        accountId,
        position,
        isMain,
        status,
        currency,
        rejection,
        correlationId,
        verificationSentAt,
        verificationAttempts,
        createdAt,
        now);
  }

  AccountLink verificationResent(Instant now) {
    return new AccountLink(
        id,
        accountId,
        position,
        main,
        status,
        currency,
        rejection,
        correlationId,
        now,
        verificationAttempts + 1,
        createdAt,
        updatedAt);
  }

  /** Whether Account Service has not answered yet. */
  public boolean isPending() {
    return status == LinkStatus.PENDING;
  }

  /** Whether Account Service confirmed the account. */
  public boolean isLinked() {
    return status == LinkStatus.LINKED;
  }
}
