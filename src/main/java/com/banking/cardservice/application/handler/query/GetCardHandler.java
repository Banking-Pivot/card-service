package com.banking.cardservice.application.handler.query;

import com.banking.cardservice.application.exception.CardNotFoundException;
import com.banking.cardservice.application.port.in.GetCardUseCase;
import com.banking.cardservice.application.port.out.CardStorePort;
import com.banking.cardservice.domain.model.Card;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Reads one card with its linked accounts. */
public class GetCardHandler implements GetCardUseCase {

  private final CardStorePort cards;

  public GetCardHandler(CardStorePort cards) {
    this.cards = Objects.requireNonNull(cards);
  }

  @Override
  public Mono<Card> execute(UUID cardId) {
    return cards
        .findById(cardId)
        .switchIfEmpty(Mono.error(() -> new CardNotFoundException(cardId)));
  }
}
