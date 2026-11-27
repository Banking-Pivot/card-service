package com.banking.cardservice.application.model;

import com.banking.cardservice.domain.model.HolderDocument;
import java.util.List;
import java.util.UUID;

/** The answer to "which accounts does this card use". */
public sealed interface CardResolution {

  /**
   * The card is active.
   *
   * @param customerId customer who holds the card
   * @param mainAccountId account the card charges first
   * @param accountIds the linked accounts, main first, then in the order they were linked
   * @param holderDocument identity document of the customer, or null when the card does not know it
   */
  record Resolved(
      UUID cardId,
      UUID customerId,
      UUID mainAccountId,
      List<UUID> accountIds,
      HolderDocument holderDocument)
      implements CardResolution {

    public Resolved {
      accountIds = List.copyOf(accountIds);
    }
  }

  /**
   * The card cannot be used.
   *
   * @param code {@code CARD_NOT_FOUND} or {@code CARD_NOT_ACTIVE}
   * @param message explanation
   */
  record Refused(UUID cardId, String code, String message) implements CardResolution {}
}
