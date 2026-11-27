package com.banking.cardservice.application.handler.command;

import com.banking.cardservice.application.command.LinkAccountCommand;
import com.banking.cardservice.application.port.in.LinkAccountUseCase;
import com.banking.cardservice.domain.model.Card;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * Links another account to a card: stores the link as pending and asks Account Service whether the
 * account belongs to the customer of the card.
 */
public class LinkAccountHandler implements LinkAccountUseCase {

  private final CardUpdater updater;
  private final VerificationDispatcher dispatcher;
  private final Clock clock;
  private final Supplier<UUID> ids;

  /**
   * Creates the handler.
   *
   * @param ids source of identifiers for the new link
   */
  public LinkAccountHandler(
      CardUpdater updater, VerificationDispatcher dispatcher, Clock clock, Supplier<UUID> ids) {
    this.updater = Objects.requireNonNull(updater);
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.clock = Objects.requireNonNull(clock);
    this.ids = Objects.requireNonNull(ids);
  }

  @Override
  public Mono<Card> execute(LinkAccountCommand command) {
    return Mono.fromSupplier(ids)
        .flatMap(
            linkId ->
                updater
                    .change(
                        command.cardId(),
                        card ->
                            card.linkAccount(
                                linkId,
                                command.accountId(),
                                command.correlationId(),
                                clock.instant()))
                    .flatMap(card -> dispatcher.dispatch(card, linkId).thenReturn(card)));
  }
}
