package com.banking.cardservice.application.handler.command;

import com.banking.cardservice.application.exception.CardConcurrentModificationException;
import com.banking.cardservice.application.port.in.ResendPendingVerificationsUseCase;
import com.banking.cardservice.application.port.out.CardStorePort;
import com.banking.cardservice.domain.model.AccountLink;
import com.banking.cardservice.domain.model.Card;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Asks Account Service again about every account that has waited too long for its answer.
 *
 * <p>This is what makes a card leave PENDING_VERIFICATION, and a link leave PENDING, when a message
 * is lost on the way: the question never reached the broker, Account Service was down, or its
 * answer could not be stored here. The question changes nothing in Account Service, so asking twice
 * is harmless. A link is asked about for as long as it waits; each time is counted and logged.
 */
public class ResendPendingVerificationsHandler implements ResendPendingVerificationsUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(ResendPendingVerificationsHandler.class);

  private final CardStorePort cards;
  private final VerificationDispatcher dispatcher;
  private final Clock clock;
  private final Duration resendAfter;
  private final int batchSize;

  /**
   * Creates the handler.
   *
   * @param resendAfter how long a question waits for its answer before it is asked again
   * @param batchSize most pending accounts handled in one run
   */
  public ResendPendingVerificationsHandler(
      CardStorePort cards,
      VerificationDispatcher dispatcher,
      Clock clock,
      Duration resendAfter,
      int batchSize) {
    this.cards = Objects.requireNonNull(cards);
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.clock = Objects.requireNonNull(clock);
    this.resendAfter = Objects.requireNonNull(resendAfter);
    this.batchSize = batchSize;
  }

  @Override
  public Mono<Long> execute() {
    return Mono.fromSupplier(clock::instant)
        .flatMap(
            now ->
                cards
                    .findWithVerificationSentBefore(now.minus(resendAfter), batchSize)
                    .concatMap(card -> resend(card, now))
                    .reduce(0L, Long::sum));
  }

  // The new send time is written first: if an answer arrived in the meantime the write fails and
  // there is nothing to ask again.
  private Mono<Long> resend(Card card, Instant now) {
    Instant sentBefore = now.minus(resendAfter);
    List<AccountLink> waiting =
        card.pendingLinks().stream()
            .filter(link -> link.verificationSentAt().isBefore(sentBefore))
            .toList();
    if (waiting.isEmpty()) {
      return Mono.just(0L);
    }
    Card marked = card;
    for (AccountLink link : waiting) {
      marked = marked.verificationResent(link.id(), now);
    }
    Card stored = marked;
    return cards
        .update(stored, card.version())
        .thenMany(Flux.fromIterable(waiting))
        .concatMap(
            link -> {
              LOG.warn(
                  "No answer in time, asking about the account again. corrId={}, cardId={}, "
                      + "linkId={}, attempt={}",
                  link.correlationId(),
                  stored.id(),
                  link.id(),
                  link.verificationAttempts() + 1);
              return dispatcher.dispatch(stored, link.id()).thenReturn(link);
            })
        .count()
        .onErrorResume(CardConcurrentModificationException.class, moved -> Mono.just(0L));
  }
}
