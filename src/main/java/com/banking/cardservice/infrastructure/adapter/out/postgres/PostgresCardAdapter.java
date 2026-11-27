package com.banking.cardservice.infrastructure.adapter.out.postgres;

import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

import com.banking.cardservice.application.exception.CardConcurrentModificationException;
import com.banking.cardservice.application.exception.CardNumberTakenException;
import com.banking.cardservice.application.model.ResultPage;
import com.banking.cardservice.application.port.out.CardStorePort;
import com.banking.cardservice.application.query.ListCardsQuery;
import com.banking.cardservice.domain.model.AccountLink;
import com.banking.cardservice.domain.model.Card;
import com.banking.cardservice.domain.model.CardStatus;
import com.banking.cardservice.domain.model.LinkStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps cards and their linked accounts in PostgreSQL through Spring Data, with no SQL written by
 * hand.
 *
 * <p>A card and its accounts are written in one database transaction. Every update is conditioned
 * on the version the card was read with, so a request, an answer of Account Service and the
 * periodic re-send never overwrite each other: the one that arrives second is told the card
 * changed.
 */
@Component
public class PostgresCardAdapter implements CardStorePort {

  private static final String CARD_NUMBER = "uk_cards_number";
  private static final String CARD_ID = "cardId";
  private static final String CUSTOMER_ID = "customerId";
  private static final String ID = "id";
  private static final String STATUS = "status";
  private static final String VERIFICATION_SENT_AT = "verificationSentAt";

  private final R2dbcEntityTemplate template;
  private final TransactionalOperator transaction;

  public PostgresCardAdapter(R2dbcEntityTemplate template, TransactionalOperator transaction) {
    this.template = template;
    this.transaction = transaction;
  }

  @Override
  public Mono<Void> insert(Card card) {
    return template
        .insert(CardRow.of(card))
        .thenMany(Flux.fromIterable(card.accounts()))
        .concatMap(link -> template.insert(CardAccountRow.of(card.id(), link)))
        .then()
        .as(transaction::transactional)
        .onErrorMap(
            error -> PostgresErrors.isUniqueViolation(error, CARD_NUMBER),
            CardNumberTakenException::new);
  }

  @Override
  public Mono<Card> findById(UUID cardId) {
    return template.selectOne(query(where(ID).is(cardId)), CardRow.class).flatMap(this::withLinks);
  }

  @Override
  public Mono<Card> findByLinkId(UUID linkId) {
    return template
        .selectOne(query(where(ID).is(linkId)), CardAccountRow.class)
        .flatMap(link -> findById(link.cardId()));
  }

  @Override
  public Mono<Void> update(Card changed, long expectedVersion) {
    return template
        .update(CardRow.class)
        .matching(query(where(ID).is(changed.id()).and("version").is(expectedVersion)))
        .apply(changesOf(changed))
        .flatMap(
            rows ->
                rows == 1
                    ? writeLinks(changed)
                    : Mono.<Void>error(new CardConcurrentModificationException(changed.id())))
        .as(transaction::transactional);
  }

  @Override
  public Flux<Card> findWithVerificationSentBefore(Instant sentBefore, int limit) {
    return template
        .select(
            query(
                    where(STATUS)
                        .is(LinkStatus.PENDING.name())
                        .and(VERIFICATION_SENT_AT)
                        .lessThan(sentBefore))
                .sort(Sort.by(VERIFICATION_SENT_AT))
                .limit(limit),
            CardAccountRow.class)
        .map(CardAccountRow::cardId)
        .distinct()
        .concatMap(this::findById);
  }

  @Override
  public Mono<Long> countNotRejected(UUID customerId) {
    return template.count(
        query(where(CUSTOMER_ID).is(customerId).and(STATUS).not(CardStatus.REJECTED.name())),
        CardRow.class);
  }

  @Override
  public Mono<ResultPage<Card>> findPage(ListCardsQuery request) {
    Criteria filter =
        request.customerId() == null
            ? Criteria.empty()
            : where(CUSTOMER_ID).is(request.customerId());
    return Mono.zip(
            template
                .select(
                    query(filter)
                        .sort(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc(ID)))
                        .offset(request.pageRequest().offset())
                        .limit(request.pageRequest().size()),
                    CardRow.class)
                .concatMap(this::withLinks)
                .collectList(),
            template.count(query(filter), CardRow.class))
        .map(page -> ResultPage.of(page.getT1(), request.pageRequest(), page.getT2()));
  }

  private Mono<Card> withLinks(CardRow card) {
    return template
        .select(
            query(where(CARD_ID).is(card.id())).sort(Sort.by("linkPosition")), CardAccountRow.class)
        .map(CardAccountRow::toLink)
        .collectList()
        .map(card::toCard);
  }

  // Links the card no longer has are removed first, so a new attempt can take the place of a
  // rejected one. Then the rest are written, the main account last: the account that stops being
  // the main one is updated before the new one takes its place, and the database never sees two.
  private Mono<Void> writeLinks(Card card) {
    List<UUID> kept = card.accounts().stream().map(AccountLink::id).toList();
    List<AccountLink> ordered =
        card.accounts().stream().sorted(Comparator.comparing(AccountLink::main)).toList();
    return template
        .delete(query(where(CARD_ID).is(card.id()).and(ID).notIn(kept)), CardAccountRow.class)
        .thenMany(Flux.fromIterable(ordered))
        .concatMap(link -> writeLink(card.id(), link))
        .then();
  }

  private Mono<Void> writeLink(UUID cardId, AccountLink link) {
    return template
        .update(CardAccountRow.class)
        .matching(query(where(ID).is(link.id())))
        .apply(changesOf(link))
        .flatMap(
            rows ->
                rows == 1
                    ? Mono.<Void>empty()
                    : template.insert(CardAccountRow.of(cardId, link)).then());
  }

  // The customer, the number and the creation time never change. The rejection and the holder
  // document only go from empty to set, so while they are empty they are left out of the
  // statement.
  private static Update changesOf(Card changed) {
    Update update =
        Update.update(STATUS, changed.status().name())
            .set("updatedAt", changed.updatedAt())
            .set("version", changed.version());
    if (changed.rejection() != null) {
      update =
          update
              .set("rejectionCode", changed.rejection().code())
              .set("rejectionMessage", changed.rejection().message());
    }
    if (changed.holderDocument() != null) {
      update =
          update
              .set("holderDocumentType", changed.holderDocument().type())
              .set("holderDocumentNumber", changed.holderDocument().number());
    }
    return update;
  }

  // The account, the position and the correlation identifier of a link never change. The currency
  // and the rejection only go from empty to set.
  private static Update changesOf(AccountLink link) {
    Update update =
        Update.update("mainAccount", link.main())
            .set(STATUS, link.status().name())
            .set(VERIFICATION_SENT_AT, link.verificationSentAt())
            .set("verificationAttempts", link.verificationAttempts())
            .set("updatedAt", link.updatedAt());
    if (link.currency() != null) {
      update = update.set("currency", link.currency());
    }
    if (link.rejection() != null) {
      update =
          update
              .set("rejectionCode", link.rejection().code())
              .set("rejectionMessage", link.rejection().message());
    }
    return update;
  }
}
