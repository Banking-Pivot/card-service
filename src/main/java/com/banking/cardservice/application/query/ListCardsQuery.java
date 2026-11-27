package com.banking.cardservice.application.query;

import java.util.Objects;
import java.util.UUID;

/**
 * Filter and page of the card list.
 *
 * @param pageRequest which page
 * @param customerId only the cards of this customer, or null for all
 */
public record ListCardsQuery(PageRequest pageRequest, UUID customerId) {

  public ListCardsQuery {
    Objects.requireNonNull(pageRequest, "pageRequest");
  }
}
