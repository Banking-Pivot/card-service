package com.banking.cardservice.support;

import com.banking.cardservice.application.exception.CardConcurrentModificationException;
import com.banking.cardservice.application.exception.CardNumberTakenException;
import com.banking.cardservice.application.model.ResultPage;
import com.banking.cardservice.application.port.out.CardStorePort;
import com.banking.cardservice.application.query.ListCardsQuery;
import com.banking.cardservice.domain.model.Card;
import com.banking.cardservice.domain.model.enums.CardStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A store that keeps cards in memory and can be told to fail like the real one. */
public final class InMemoryCardStore implements CardStorePort {

  private final Map<UUID, Card> cards = new LinkedHashMap<>();
  private int conflicts;
  private int takenNumbers;
  private int updates;

  public void put(Card card) {
    cards.put(card.id(), card);
  }

  public Card get(UUID cardId) {
    return cards.get(cardId);
  }

  public int size() {
    return cards.size();
  }

  /** How many updates were written. */
  public int updates() {
    return updates;
  }

  /** The next updates fail as if another operation had changed the card. */
  public void failNextUpdates(int count) {
    conflicts = count;
  }

  /** The next inserts fail as if another card had the generated number. */
  public void rejectNextNumbers(int count) {
    takenNumbers = count;
  }

  @Override
  public Mono<Void> insert(Card card) {
    return Mono.defer(
        () -> {
          if (takenNumbers > 0) {
            takenNumbers--;
            return Mono.error(new CardNumberTakenException(new IllegalStateException("taken")));
          }
          cards.put(card.id(), card);
          return Mono.empty();
        });
  }

  @Override
  public Mono<Card> findById(UUID cardId) {
    return Mono.defer(() -> Mono.justOrEmpty(cards.get(cardId)));
  }

  @Override
  public Mono<Card> findByLinkId(UUID linkId) {
    return Mono.defer(
        () ->
            Mono.justOrEmpty(
                cards.values().stream().filter(card -> card.link(linkId).isPresent()).findFirst()));
  }

  @Override
  public Mono<Void> update(Card changed, long expectedVersion) {
    return Mono.defer(
        () -> {
          Card stored = cards.get(changed.id());
          if (conflicts > 0 || stored == null || stored.version() != expectedVersion) {
            conflicts = Math.max(0, conflicts - 1);
            return Mono.error(new CardConcurrentModificationException(changed.id()));
          }
          cards.put(changed.id(), changed);
          updates++;
          return Mono.empty();
        });
  }

  @Override
  public Flux<Card> findWithVerificationSentBefore(Instant sentBefore, int limit) {
    return Flux.defer(
        () ->
            Flux.fromIterable(
                cards.values().stream()
                    .filter(
                        card ->
                            card.pendingLinks().stream()
                                .anyMatch(link -> link.verificationSentAt().isBefore(sentBefore)))
                    .limit(limit)
                    .toList()));
  }

  @Override
  public Mono<Long> countHeld(UUID customerId) {
    return Mono.fromSupplier(
        () ->
            cards.values().stream()
                .filter(card -> card.customerId().equals(customerId))
                .filter(card -> card.status() != CardStatus.REJECTED)
                .filter(card -> card.status() != CardStatus.CANCELLED)
                .count());
  }

  @Override
  public Mono<ResultPage<Card>> findPage(ListCardsQuery query) {
    return Mono.fromSupplier(
        () -> {
          List<Card> matching =
              cards.values().stream()
                  .filter(
                      card ->
                          query.customerId() == null
                              || card.customerId().equals(query.customerId()))
                  .sorted(Comparator.comparing(Card::createdAt).reversed())
                  .toList();
          List<Card> items =
              matching.stream()
                  .skip(query.pageRequest().offset())
                  .limit(query.pageRequest().size())
                  .toList();
          return ResultPage.of(items, query.pageRequest(), matching.size());
        });
  }
}
