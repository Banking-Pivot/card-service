package com.banking.cardservice.infrastructure.adapter.out.postgres;

import com.banking.cardservice.domain.model.AccountLink;
import com.banking.cardservice.domain.model.Card;
import com.banking.cardservice.domain.model.enums.CardStatus;
import com.banking.cardservice.domain.model.vo.CardNumber;
import com.banking.cardservice.domain.model.vo.HolderDocument;
import com.banking.cardservice.domain.model.vo.Rejection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Row of {@code cards}. Enums are stored by name. {@code version} is managed by the domain and
 * checked by the adapter on every update.
 */
@Table("cards")
public record CardRow(
    @Id UUID id,
    UUID customerId,
    String cardNumber,
    CardStatus status,
    String rejectionCode,
    String rejectionMessage,
    String holderDocumentType,
    String holderDocumentNumber,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  static CardRow of(Card card) {
    Rejection rejection = card.rejection();
    HolderDocument holder = card.holderDocument();
    return new CardRow(
        card.id(),
        card.customerId(),
        card.number().value(),
        card.status(),
        rejection == null ? null : rejection.code(),
        rejection == null ? null : rejection.message(),
        holder == null ? null : holder.type(),
        holder == null ? null : holder.number(),
        card.createdAt(),
        card.updatedAt(),
        card.version());
  }

  Card toCard(List<AccountLink> accounts) {
    return new Card(
        id,
        customerId,
        new CardNumber(cardNumber),
        status,
        rejectionCode == null ? null : new Rejection(rejectionCode, rejectionMessage),
        holderDocumentType == null
            ? null
            : new HolderDocument(holderDocumentType, holderDocumentNumber),
        accounts,
        createdAt,
        updatedAt,
        version);
  }
}
