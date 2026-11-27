package com.banking.cardservice.application.handler.command;

import com.banking.cardservice.application.exception.CardConcurrentModificationException;
import com.banking.cardservice.application.exception.CardNotFoundException;
import com.banking.cardservice.application.port.out.CardStorePort;
import com.banking.cardservice.domain.model.Card;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Reads a card, changes it and writes it back. When somebody else changed the card in between (an
 * answer of Account Service, the periodic re-send), the change is applied again on the fresh card.
 */
public class CardUpdater {

  private static final int ATTEMPTS_ON_CONFLICT = 3;

  private final CardStorePort cards;

  public CardUpdater(CardStorePort cards) {
    this.cards = Objects.requireNonNull(cards);
  }

  /**
   * Applies the change to the card and stores the result.
   *
   * @param change returns the changed card, or the same instance when there is nothing to change
   * @return the card after the change; {@code CardNotFoundException} when it does not exist, or
   *     whatever the change throws
   */
  public Mono<Card> change(UUID cardId, UnaryOperator<Card> change) {
    return Mono.defer(() -> changeOnce(cardId, change))
        .retryWhen(
            Retry.max(ATTEMPTS_ON_CONFLICT)
                .filter(CardConcurrentModificationException.class::isInstance)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
  }

  private Mono<Card> changeOnce(UUID cardId, UnaryOperator<Card> change) {
    return cards
        .findById(cardId)
        .switchIfEmpty(Mono.error(() -> new CardNotFoundException(cardId)))
        .flatMap(
            card -> {
              Card changed = change.apply(card);
              return changed == card
                  ? Mono.just(card)
                  : cards.update(changed, card.version()).thenReturn(changed);
            });
  }
}
