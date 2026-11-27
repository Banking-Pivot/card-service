package com.banking.cardservice.application.handler.command;

import com.banking.cardservice.application.port.in.ManageCardUseCase;
import com.banking.cardservice.domain.model.Card;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** The changes to a card that need nobody else. The card itself decides whether each is allowed. */
public class ManageCardHandler implements ManageCardUseCase {

  private final CardUpdater updater;
  private final Clock clock;

  public ManageCardHandler(CardUpdater updater, Clock clock) {
    this.updater = Objects.requireNonNull(updater);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public Mono<Card> changeMainAccount(UUID cardId, UUID accountId) {
    return updater.change(cardId, card -> card.changeMainAccount(accountId, clock.instant()));
  }

  @Override
  public Mono<Card> unlinkAccount(UUID cardId, UUID accountId) {
    return updater.change(cardId, card -> card.unlinkAccount(accountId, clock.instant()));
  }

  @Override
  public Mono<Card> block(UUID cardId) {
    return updater.change(cardId, card -> card.block(clock.instant()));
  }

  @Override
  public Mono<Card> unblock(UUID cardId) {
    return updater.change(cardId, card -> card.unblock(clock.instant()));
  }
}
