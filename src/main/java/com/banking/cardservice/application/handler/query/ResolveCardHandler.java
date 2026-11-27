package com.banking.cardservice.application.handler.query;

import com.banking.cardservice.application.model.CardResolution;
import com.banking.cardservice.application.port.in.ResolveCardUseCase;
import com.banking.cardservice.application.port.out.CardStorePort;
import com.banking.cardservice.domain.model.Card;
import com.banking.cardservice.domain.model.enums.CardStatus;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Answers which accounts a card uses. Only an active card has an answer: a card that is pending,
 * rejected or blocked cannot pay, and neither can one that does not exist.
 */
public class ResolveCardHandler implements ResolveCardUseCase {

  public static final String CARD_NOT_FOUND = "CARD_NOT_FOUND";
  public static final String CARD_NOT_ACTIVE = "CARD_NOT_ACTIVE";

  private final CardStorePort cards;

  public ResolveCardHandler(CardStorePort cards) {
    this.cards = Objects.requireNonNull(cards);
  }

  @Override
  public Mono<CardResolution> execute(UUID cardId) {
    return cards
        .findById(cardId)
        .map(ResolveCardHandler::resolve)
        .defaultIfEmpty(
            new CardResolution.Refused(cardId, CARD_NOT_FOUND, "The card does not exist."));
  }

  private static CardResolution resolve(Card card) {
    if (card.status() != CardStatus.ACTIVE) {
      return new CardResolution.Refused(card.id(), CARD_NOT_ACTIVE, "The card is not active.");
    }
    return new CardResolution.Resolved(
        card.id(),
        card.customerId(),
        card.mainAccountId(),
        card.chargeableAccountIds(),
        card.holderDocument());
  }
}
