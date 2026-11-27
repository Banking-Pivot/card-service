package com.banking.cardservice.application.model;

import com.banking.cardservice.domain.model.Card;
import com.banking.cardservice.domain.model.HolderDocument;
import com.banking.cardservice.domain.model.Rejection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** What Account Service answered about an account a card wants to link. */
public sealed interface AccountVerification {

  /** Identifier of the link the answer is about. */
  UUID linkId();

  /**
   * Applies the answer to the card.
   *
   * @return the changed card, or empty when the card is not waiting for this answer
   */
  Optional<Card> applyTo(Card card, Instant now);

  /**
   * The account exists and belongs to the customer of the card.
   *
   * @param currency currency of the account
   * @param holderDocument identity document of the customer, or null when the answer has none
   */
  record Verified(UUID linkId, String currency, HolderDocument holderDocument)
      implements AccountVerification {

    /** The link and the currency are required. */
    public Verified {
      Objects.requireNonNull(linkId, "linkId");
      Objects.requireNonNull(currency, "currency");
    }

    /** A confirmation that does not say who the customer is. */
    public Verified(UUID linkId, String currency) {
      this(linkId, currency, null);
    }

    @Override
    public Optional<Card> applyTo(Card card, Instant now) {
      return card.accountVerified(linkId, currency, holderDocument, now);
    }
  }

  /**
   * The account cannot be linked.
   *
   * @param code error code of Account Service
   * @param message its explanation
   */
  record Refused(UUID linkId, String code, String message) implements AccountVerification {

    /** All three values are required. */
    public Refused {
      Objects.requireNonNull(linkId, "linkId");
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(message, "message");
    }

    @Override
    public Optional<Card> applyTo(Card card, Instant now) {
      return card.accountRejected(linkId, new Rejection(code, message), now);
    }
  }
}
