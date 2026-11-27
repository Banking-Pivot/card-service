package com.banking.cardservice.application.port.out;

import com.banking.cardservice.application.model.ResultPage;
import com.banking.cardservice.application.query.ListCardsQuery;
import com.banking.cardservice.domain.model.Card;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where cards and their linked accounts are kept. */
public interface CardStorePort {

  /**
   * Stores a new card with its accounts.
   *
   * @return completion, or {@code CardNumberTakenException} when another card has the number
   */
  Mono<Void> insert(Card card);

  /** The card with its accounts, or empty. */
  Mono<Card> findById(UUID cardId);

  /** The card that has the link with that identifier, or empty. */
  Mono<Card> findByLinkId(UUID linkId);

  /**
   * Writes a changed card and its accounts, only if the stored card still has the version it was
   * read with.
   *
   * @return completion, or {@code CardConcurrentModificationException} when the card changed in
   *     between
   */
  Mono<Void> update(Card changed, long expectedVersion);

  /**
   * Cards with an account still waiting for Account Service whose question was last sent before the
   * given instant, oldest question first.
   *
   * @param limit most pending accounts looked at
   */
  Flux<Card> findWithVerificationSentBefore(Instant sentBefore, int limit);

  /** How many cards the customer has, leaving out the ones that were rejected. */
  Mono<Long> countNotRejected(UUID customerId);

  /** One page of cards, newest first, with the total. */
  Mono<ResultPage<Card>> findPage(ListCardsQuery query);
}
