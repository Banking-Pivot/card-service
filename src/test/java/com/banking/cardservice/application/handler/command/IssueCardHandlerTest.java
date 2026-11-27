package com.banking.cardservice.application.handler.command;

import static com.banking.cardservice.support.Cards.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.cardservice.application.command.IssueCardCommand;
import com.banking.cardservice.application.exception.CardNumberTakenException;
import com.banking.cardservice.application.model.VerificationRequest;
import com.banking.cardservice.domain.exception.CardRuleViolationException;
import com.banking.cardservice.domain.exception.RuleViolation;
import com.banking.cardservice.domain.model.Card;
import com.banking.cardservice.domain.model.enums.CardStatus;
import com.banking.cardservice.domain.model.vo.CardNumber;
import com.banking.cardservice.domain.model.vo.Rejection;
import com.banking.cardservice.support.Cards;
import com.banking.cardservice.support.InMemoryCardStore;
import com.banking.cardservice.support.RecordingPublisher;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IssueCardHandlerTest {

  private static final UUID ACCOUNT = id(21);
  private static final IssueCardCommand COMMAND =
      new IssueCardCommand(Cards.CUSTOMER, ACCOUNT, "corr-issue");

  private final InMemoryCardStore store = new InMemoryCardStore();
  private final RecordingPublisher publisher = new RecordingPublisher();
  private final AtomicInteger numbers = new AtomicInteger();
  private final AtomicInteger ids = new AtomicInteger();
  private final IssueCardHandler handler =
      new IssueCardHandler(
          store,
          new VerificationDispatcher(publisher),
          () -> CardNumber.generate(numbers::incrementAndGet),
          Cards.clockAt(0),
          () -> id(ids.incrementAndGet()),
          3);

  @Test
  void theCardIsStoredWaitingAndAccountServiceIsAskedAboutItsMainAccount() {
    Card card = handler.execute(COMMAND).block();

    assertThat(card.id()).isEqualTo(id(1));
    assertThat(card.status()).isEqualTo(CardStatus.PENDING_VERIFICATION);
    assertThat(card.customerId()).isEqualTo(Cards.CUSTOMER);
    assertThat(card.mainAccountId()).isEqualTo(ACCOUNT);
    assertThat(card.createdAt()).isEqualTo(Cards.START);
    assertThat(store.get(id(1))).isEqualTo(card);
    assertThat(publisher.sent())
        .containsExactly(
            new VerificationRequest(id(2), ACCOUNT, Cards.CUSTOMER, "corr-issue", true));
  }

  @Test
  void aNumberAnotherCardAlreadyHasIsReplacedByANewOne() {
    store.rejectNextNumbers(2);

    Card card = handler.execute(COMMAND).block();

    assertThat(store.size()).isEqualTo(1);
    assertThat(store.get(card.id()).number()).isEqualTo(card.number());
    assertThat(publisher.sent()).hasSize(1);
    assertThat(publisher.sent().get(0).linkId()).isEqualTo(card.accounts().get(0).id());
  }

  @Test
  void issuingGivesUpWhenEveryNumberIsTaken() {
    store.rejectNextNumbers(100);

    assertThatThrownBy(() -> handler.execute(COMMAND).block())
        .isInstanceOf(CardNumberTakenException.class);

    assertThat(store.size()).isZero();
    assertThat(publisher.sent()).isEmpty();
    // The first try and five more.
    assertThat(ids.get()).isEqualTo(12);
  }

  @Test
  void theCardIsKeptWhenTheQuestionCannotBeSent() {
    publisher.goDown();

    Card card = handler.execute(COMMAND).block();

    assertThat(store.get(card.id()).pendingLinks()).hasSize(1);
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void aCustomerWithAsManyCardsAsTheBankAllowsGetsNoMore() {
    store.put(Cards.pending(id(101), id(111), id(121)));
    store.put(Cards.active(id(102), id(112), id(122)));
    store.put(Cards.active(id(103), id(113), id(123)).block(Cards.START));

    assertThatThrownBy(() -> handler.execute(COMMAND).block())
        .isInstanceOfSatisfying(
            CardRuleViolationException.class,
            broken -> {
              assertThat(broken.violation()).isEqualTo(RuleViolation.CARD_LIMIT_REACHED);
              assertThat(broken)
                  .hasMessage("The customer already has the maximum number of cards.");
            });

    assertThat(store.size()).isEqualTo(3);
    assertThat(publisher.sent()).isEmpty();
    // Nothing was generated for a card that is not issued.
    assertThat(ids.get()).isZero();
    assertThat(numbers.get()).isZero();
  }

  @Test
  void rejectedCardsAndTheCardsOfOtherCustomersDoNotCountTowardsTheLimit() {
    store.put(Cards.active(id(101), id(111), id(121)));
    store.put(Cards.active(id(102), id(112), id(122)));
    store.put(
        Cards.pending(id(103), id(113), id(123))
            .accountRejected(
                id(113),
                new Rejection("OVERDUE_DEBT", "The customer has overdue debt."),
                Cards.START)
            .orElseThrow());
    store.put(
        Card.issue(id(104), id(901), Cards.NUMBER, id(114), id(124), "corr-other", Cards.START));

    Card card = handler.execute(COMMAND).block();

    assertThat(card.status()).isEqualTo(CardStatus.PENDING_VERIFICATION);
    assertThat(store.countNotRejected(Cards.CUSTOMER).block()).isEqualTo(3);
    assertThat(store.countNotRejected(id(901)).block()).isEqualTo(1);
    assertThat(publisher.sent()).hasSize(1);
  }
}
