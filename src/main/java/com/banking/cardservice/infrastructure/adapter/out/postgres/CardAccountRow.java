package com.banking.cardservice.infrastructure.adapter.out.postgres;

import com.banking.cardservice.domain.model.AccountLink;
import com.banking.cardservice.domain.model.enums.LinkStatus;
import com.banking.cardservice.domain.model.vo.Rejection;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Row of {@code card_accounts}: one account linked to a card. Enums are stored by name. */
@Table("card_accounts")
public record CardAccountRow(
    @Id UUID id,
    UUID cardId,
    UUID accountId,
    int linkPosition,
    boolean mainAccount,
    LinkStatus status,
    String currency,
    String rejectionCode,
    String rejectionMessage,
    String correlationId,
    Instant verificationSentAt,
    int verificationAttempts,
    Instant createdAt,
    Instant updatedAt) {

  static CardAccountRow of(UUID cardId, AccountLink link) {
    Rejection rejection = link.rejection();
    return new CardAccountRow(
        link.id(),
        cardId,
        link.accountId(),
        link.position(),
        link.main(),
        link.status(),
        link.currency(),
        rejection == null ? null : rejection.code(),
        rejection == null ? null : rejection.message(),
        link.correlationId(),
        link.verificationSentAt(),
        link.verificationAttempts(),
        link.createdAt(),
        link.updatedAt());
  }

  AccountLink toLink() {
    return new AccountLink(
        id,
        accountId,
        linkPosition,
        mainAccount,
        status,
        currency,
        rejectionCode == null ? null : new Rejection(rejectionCode, rejectionMessage),
        correlationId,
        verificationSentAt,
        verificationAttempts,
        createdAt,
        updatedAt);
  }
}
