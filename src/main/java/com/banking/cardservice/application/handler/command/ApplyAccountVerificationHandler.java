package com.banking.cardservice.application.handler.command;

import com.banking.cardservice.application.exception.CardConcurrentModificationException;
import com.banking.cardservice.application.model.AccountVerification;
import com.banking.cardservice.application.port.in.ApplyAccountVerificationUseCase;
import com.banking.cardservice.application.port.out.CardStorePort;
import com.banking.cardservice.domain.model.Card;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Applies the answer of Account Service about an account to the card that asked.
 *
 * <p>Answers can arrive twice (a question that was sent again is answered again) and late. The card
 * decides: an answer for a link that is no longer pending, or that was removed, changes nothing.
 * The change is written only if the card is still as it was read; when something else touched it in
 * between, the answer is applied again on the fresh card.
 */
public class ApplyAccountVerificationHandler implements ApplyAccountVerificationUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(ApplyAccountVerificationHandler.class);
  private static final int ATTEMPTS_ON_CONFLICT = 3;

  private final CardStorePort cards;
  private final Clock clock;

  public ApplyAccountVerificationHandler(CardStorePort cards, Clock clock) {
    this.cards = Objects.requireNonNull(cards);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public Mono<Void> execute(AccountVerification verification) {
    return Mono.defer(() -> applyOnce(verification))
        .retryWhen(
            Retry.max(ATTEMPTS_ON_CONFLICT)
                .filter(CardConcurrentModificationException.class::isInstance)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
  }

  private Mono<Void> applyOnce(AccountVerification verification) {
    return cards
        .findByLinkId(verification.linkId())
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .flatMap(
            found ->
                found
                    .map(card -> apply(verification, card))
                    .orElseGet(() -> unknown(verification)));
  }

  private Mono<Void> apply(AccountVerification verification, Card card) {
    Optional<Card> changed = verification.applyTo(card, clock.instant());
    if (changed.isEmpty()) {
      LOG.info(
          "Account check ignored, the link is not waiting for it. cardId={}, linkId={}, answer={}",
          card.id(),
          verification.linkId(),
          verification.getClass().getSimpleName());
      return Mono.empty();
    }
    Card next = changed.get();
    return cards
        .update(next, card.version())
        .doOnSuccess(
            done ->
                LOG.info(
                    "Account check applied. cardId={}, linkId={}, answer={}, cardStatus={}",
                    next.id(),
                    verification.linkId(),
                    verification.getClass().getSimpleName(),
                    next.status()));
  }

  private static Mono<Void> unknown(AccountVerification verification) {
    LOG.warn(
        "Account check ignored, no card has that link. linkId={}, answer={}",
        verification.linkId(),
        verification.getClass().getSimpleName());
    return Mono.empty();
  }
}
