package com.banking.cardservice.infrastructure.adapter.out.postgres;

import static com.banking.cardservice.support.Cards.START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.cardservice.application.exception.CardConcurrentModificationException;
import com.banking.cardservice.application.exception.CardNumberTakenException;
import com.banking.cardservice.application.model.ResultPage;
import com.banking.cardservice.application.port.out.CardStorePort;
import com.banking.cardservice.application.query.ListCardsQuery;
import com.banking.cardservice.application.query.PageRequest;
import com.banking.cardservice.domain.model.AccountLink;
import com.banking.cardservice.domain.model.Card;
import com.banking.cardservice.domain.model.CardNumber;
import com.banking.cardservice.domain.model.HolderDocument;
import com.banking.cardservice.domain.model.Rejection;
import com.banking.cardservice.support.IntegrationTest;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The store of cards against a real PostgreSQL. */
class PostgresCardAdapterTest extends IntegrationTest {

  private static final SecureRandom RANDOM = new SecureRandom();

  @Autowired private CardStorePort store;

  private final UUID customer = UUID.randomUUID();
  private final UUID mainLink = UUID.randomUUID();
  private final UUID secondLink = UUID.randomUUID();
  private final UUID mainAccount = UUID.randomUUID();
  private final UUID secondAccount = UUID.randomUUID();

  @Test
  void aCardComesBackAsItWasStoredThroughEveryChange() {
    UUID id = UUID.randomUUID();
    Card pending = fresh(id, customer, mainLink, mainAccount, 0);
    store.insert(pending).block();
    assertStored(pending);

    Card active = pending.accountVerified(mainLink, "PEN", START.plusSeconds(1)).orElseThrow();
    store.update(active, pending.version()).block();
    assertStored(active);

    Card asked = active.linkAccount(secondLink, secondAccount, "corr-2", START.plusSeconds(2));
    store.update(asked, active.version()).block();
    assertStored(asked);
    assertThat(store.findByLinkId(secondLink).block()).usingRecursiveComparison().isEqualTo(asked);

    Card resent = asked.verificationResent(secondLink, START.plusSeconds(40));
    store.update(resent, asked.version()).block();
    assertStored(resent);

    Card linked = resent.accountVerified(secondLink, "USD", START.plusSeconds(41)).orElseThrow();
    store.update(linked, resent.version()).block();
    assertStored(linked);

    // The database allows one main account per card: the old one must be written first.
    Card swapped = linked.changeMainAccount(secondAccount, START.plusSeconds(50));
    store.update(swapped, linked.version()).block();
    assertStored(swapped);
    assertThat(store.findById(id).block().mainAccountId()).isEqualTo(secondAccount);

    Card back = swapped.changeMainAccount(mainAccount, START.plusSeconds(51));
    store.update(back, swapped.version()).block();
    assertStored(back);

    Card blocked = back.block(START.plusSeconds(60));
    store.update(blocked, back.version()).block();
    assertStored(blocked);

    Card unlinked = blocked.unblock(START.plusSeconds(61)).unlinkAccount(secondAccount, START);
    store.update(unlinked, blocked.version()).block();
    assertStored(unlinked);
    assertThat(store.findByLinkId(secondLink).block()).isNull();
  }

  @Test
  void aRefusedAccountKeepsItsReasonAndANewTryTakesItsPlace() {
    UUID id = UUID.randomUUID();
    Rejection reason = new Rejection("ACCOUNT_NOT_OWNED", "The customer is not a holder.");
    Card active =
        fresh(id, customer, mainLink, mainAccount, 0)
            .accountVerified(mainLink, "PEN", START.plusSeconds(1))
            .orElseThrow();
    store.insert(active).block();
    Card refused =
        active
            .linkAccount(secondLink, secondAccount, "corr-2", START.plusSeconds(2))
            .accountRejected(secondLink, reason, START.plusSeconds(3))
            .orElseThrow();
    store.update(refused, active.version()).block();
    assertStored(refused);

    UUID retryLink = UUID.randomUUID();
    Card again = refused.linkAccount(retryLink, secondAccount, "corr-3", START.plusSeconds(4));
    store.update(again, refused.version()).block();

    assertStored(again);
    assertThat(store.findByLinkId(secondLink).block()).isNull();
    assertThat(store.findByLinkId(retryLink).block().id()).isEqualTo(id);
  }

  @Test
  void aRejectedCardKeepsItsReason() {
    UUID id = UUID.randomUUID();
    Card pending = fresh(id, customer, mainLink, mainAccount, 0);
    store.insert(pending).block();
    Card rejected =
        pending
            .accountRejected(
                mainLink, new Rejection("ACCOUNT_NOT_FOUND", "No such account."), START)
            .orElseThrow();

    store.update(rejected, pending.version()).block();

    assertStored(rejected);
  }

  @Test
  void anUpdateOverAVersionThatMovedIsRefusedAndWritesNothing() {
    UUID id = UUID.randomUUID();
    Card pending = fresh(id, customer, mainLink, mainAccount, 0);
    store.insert(pending).block();
    Card active = pending.accountVerified(mainLink, "PEN", START.plusSeconds(1)).orElseThrow();
    store.update(active, pending.version()).block();
    Card late =
        pending
            .accountRejected(mainLink, new Rejection("ACCOUNT_NOT_FOUND", "Late."), START)
            .orElseThrow();

    assertThatThrownBy(() -> store.update(late, pending.version()).block())
        .isInstanceOf(CardConcurrentModificationException.class)
        .hasMessageContaining(id.toString());
    assertThatThrownBy(
            () ->
                store
                    .update(fresh(UUID.randomUUID(), customer, mainLink, mainAccount, 0), 0)
                    .block())
        .isInstanceOf(CardConcurrentModificationException.class);

    assertStored(active);
  }

  @Test
  void aNumberAnotherCardHasIsReportedAndNothingOfTheNewCardIsKept() {
    Card first = fresh(UUID.randomUUID(), customer, mainLink, mainAccount, 0);
    store.insert(first).block();
    UUID duplicateId = UUID.randomUUID();
    Card duplicate =
        Card.issue(duplicateId, customer, first.number(), secondLink, secondAccount, "corr", START);

    assertThatThrownBy(() -> store.insert(duplicate).block())
        .isInstanceOf(CardNumberTakenException.class);

    assertThat(store.findById(duplicateId).block()).isNull();
    assertThat(store.findByLinkId(secondLink).block()).isNull();
    assertStored(first);
  }

  @Test
  void anIdentifierNobodyHasFindsNothing() {
    assertThat(store.findById(UUID.randomUUID()).block()).isNull();
    assertThat(store.findByLinkId(UUID.randomUUID()).block()).isNull();
  }

  @Test
  void cardsAreListedNewestFirstByPageAndByCustomer() {
    UUID oldest = UUID.randomUUID();
    UUID middle = UUID.randomUUID();
    UUID newest = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    store.insert(fresh(oldest, customer, UUID.randomUUID(), mainAccount, 0)).block();
    store.insert(fresh(middle, customer, UUID.randomUUID(), mainAccount, 10)).block();
    store.insert(fresh(newest, customer, UUID.randomUUID(), mainAccount, 20)).block();
    store.insert(fresh(other, UUID.randomUUID(), UUID.randomUUID(), mainAccount, 30)).block();

    ResultPage<Card> everyone = page(0, 20, null);
    ResultPage<Card> firstPage = page(0, 2, customer);
    ResultPage<Card> secondPage = page(1, 2, customer);

    assertThat(everyone.items())
        .extracting(Card::id)
        .containsExactly(other, newest, middle, oldest);
    assertThat(everyone.totalElements()).isEqualTo(4);
    assertThat(firstPage.items()).extracting(Card::id).containsExactly(newest, middle);
    assertThat(firstPage.items().get(0).accounts()).hasSize(1);
    assertThat(firstPage.totalElements()).isEqualTo(3);
    assertThat(firstPage.totalPages()).isEqualTo(2);
    assertThat(secondPage.items()).extracting(Card::id).containsExactly(oldest);
    assertThat(page(5, 2, customer).items()).isEmpty();
    assertThat(page(0, 2, UUID.randomUUID()).totalElements()).isZero();
  }

  @Test
  void onlyCardsWithAQuestionThatWaitedTooLongAreFoundOldestFirst() {
    UUID old = UUID.randomUUID();
    UUID older = UUID.randomUUID();
    UUID recent = UUID.randomUUID();
    UUID answered = UUID.randomUUID();
    UUID answeredLink = UUID.randomUUID();
    store.insert(fresh(old, customer, UUID.randomUUID(), mainAccount, 10)).block();
    store.insert(fresh(older, customer, UUID.randomUUID(), mainAccount, 0)).block();
    store.insert(fresh(recent, customer, UUID.randomUUID(), mainAccount, 50)).block();
    store
        .insert(
            fresh(answered, customer, answeredLink, mainAccount, 0)
                .accountVerified(answeredLink, "PEN", START)
                .orElseThrow())
        .block();

    List<UUID> waiting =
        store
            .findWithVerificationSentBefore(START.plusSeconds(30), 50)
            .map(Card::id)
            .collectList()
            .block();
    List<UUID> oneOnly =
        store
            .findWithVerificationSentBefore(START.plusSeconds(30), 1)
            .map(Card::id)
            .collectList()
            .block();

    assertThat(waiting).containsExactly(older, old);
    assertThat(oneOnly).containsExactly(older);
    assertThat(store.findWithVerificationSentBefore(START, 50).collectList().block()).isEmpty();
  }

  @Test
  void theDocumentOfTheHolderIsStoredOnceKnownAndKeptThroughLaterChanges() {
    UUID id = UUID.randomUUID();
    HolderDocument document = new HolderDocument("DNI", "45871236");
    Card pending = fresh(id, customer, mainLink, mainAccount, 0);
    store.insert(pending).block();
    assertThat(store.findById(id).block().holderDocument()).isNull();

    Card active =
        pending.accountVerified(mainLink, "PEN", document, START.plusSeconds(1)).orElseThrow();
    store.update(active, pending.version()).block();
    assertStored(active);

    Card blocked = active.block(START.plusSeconds(2));
    store.update(blocked, active.version()).block();
    assertStored(blocked);
    assertThat(store.findById(id).block().holderDocument()).isEqualTo(document);

    // A card that already knows its holder is also stored whole.
    UUID otherLink = UUID.randomUUID();
    Card known =
        fresh(UUID.randomUUID(), customer, otherLink, mainAccount, 5)
            .accountVerified(otherLink, "PEN", document, START.plusSeconds(6))
            .orElseThrow();
    store.insert(known).block();
    assertStored(known);
  }

  @Test
  void onlyTheCardsOfTheCustomerThatWereNotRejectedAreCounted() {
    UUID activeLink = UUID.randomUUID();
    UUID blockedLink = UUID.randomUUID();
    UUID rejectedLink = UUID.randomUUID();
    store.insert(fresh(UUID.randomUUID(), customer, UUID.randomUUID(), mainAccount, 0)).block();
    store
        .insert(
            fresh(UUID.randomUUID(), customer, activeLink, mainAccount, 1)
                .accountVerified(activeLink, "PEN", START.plusSeconds(2))
                .orElseThrow())
        .block();
    store
        .insert(
            fresh(UUID.randomUUID(), customer, blockedLink, mainAccount, 3)
                .accountVerified(blockedLink, "PEN", START.plusSeconds(4))
                .orElseThrow()
                .block(START.plusSeconds(5)))
        .block();
    store
        .insert(
            fresh(UUID.randomUUID(), customer, rejectedLink, mainAccount, 6)
                .accountRejected(
                    rejectedLink,
                    new Rejection("OVERDUE_DEBT", "The customer has overdue debt."),
                    START.plusSeconds(7))
                .orElseThrow())
        .block();
    UUID other = UUID.randomUUID();
    store.insert(fresh(UUID.randomUUID(), other, UUID.randomUUID(), mainAccount, 8)).block();

    assertThat(store.countNotRejected(customer).block()).isEqualTo(3);
    assertThat(store.countNotRejected(other).block()).isEqualTo(1);
    assertThat(store.countNotRejected(UUID.randomUUID()).block()).isZero();
  }

  private ResultPage<Card> page(int page, int size, UUID ofCustomer) {
    return store.findPage(new ListCardsQuery(new PageRequest(page, size), ofCustomer)).block();
  }

  private void assertStored(Card expected) {
    Card stored = store.findById(expected.id()).block();
    assertThat(stored).usingRecursiveComparison().isEqualTo(expected);
    assertThat(stored.accounts()).extracting(AccountLink::id).doesNotHaveDuplicates();
  }

  private static Card fresh(
      UUID id, UUID customerId, UUID linkId, UUID accountId, long secondsAfterStart) {
    return Card.issue(
        id,
        customerId,
        CardNumber.generate(() -> RANDOM.nextInt(10)),
        linkId,
        accountId,
        "corr-" + linkId,
        START.plusSeconds(secondsAfterStart));
  }
}
