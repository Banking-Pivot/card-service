package com.banking.cardservice.application.handler.command;

import static com.banking.cardservice.support.Cards.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.cardservice.application.command.LinkAccountCommand;
import com.banking.cardservice.application.exception.CardConcurrentModificationException;
import com.banking.cardservice.application.exception.CardNotFoundException;
import com.banking.cardservice.application.model.VerificationRequest;
import com.banking.cardservice.domain.exception.CardRuleViolationException;
import com.banking.cardservice.domain.model.AccountLink;
import com.banking.cardservice.domain.model.Card;
import com.banking.cardservice.support.Cards;
import com.banking.cardservice.support.InMemoryCardStore;
import com.banking.cardservice.support.RecordingPublisher;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LinkAccountHandlerTest {

  private static final UUID CARD = id(1);
  private static final UUID MAIN_LINK = id(11);
  private static final UUID NEW_LINK = id(12);
  private static final UUID MAIN_ACCOUNT = id(21);
  private static final UUID SECOND_ACCOUNT = id(22);
  private static final LinkAccountCommand COMMAND =
      new LinkAccountCommand(CARD, SECOND_ACCOUNT, "corr-link");

  private final InMemoryCardStore store = new InMemoryCardStore();
  private final RecordingPublisher publisher = new RecordingPublisher();
  private final LinkAccountHandler handler =
      new LinkAccountHandler(
          new CardUpdater(store),
          new VerificationDispatcher(publisher),
          Cards.clockAt(10),
          () -> NEW_LINK);

  @BeforeEach
  void anActiveCard() {
    store.put(Cards.active(CARD, MAIN_LINK, MAIN_ACCOUNT));
  }

  @Test
  void theAccountWaitsOnTheCardAndAccountServiceIsAskedAboutIt() {
    Card card = handler.execute(COMMAND).block();

    AccountLink link = card.link(NEW_LINK).orElseThrow();
    assertThat(link.isPending()).isTrue();
    assertThat(link.position()).isEqualTo(2);
    assertThat(link.createdAt()).isEqualTo(Cards.START.plusSeconds(10));
    assertThat(store.get(CARD)).isEqualTo(card);
    assertThat(publisher.sent())
        .containsExactly(
            new VerificationRequest(NEW_LINK, SECOND_ACCOUNT, Cards.CUSTOMER, "corr-link", false));
  }

  @Test
  void aCardThatDoesNotExistCannotLinkAccounts() {
    assertThatThrownBy(
            () -> handler.execute(new LinkAccountCommand(id(404), SECOND_ACCOUNT, "c")).block())
        .isInstanceOf(CardNotFoundException.class)
        .hasMessageContaining(id(404).toString());
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void aBrokenRuleStoresNothingAndAsksNothing() {
    assertThatThrownBy(
            () -> handler.execute(new LinkAccountCommand(CARD, MAIN_ACCOUNT, "c")).block())
        .isInstanceOf(CardRuleViolationException.class);

    assertThat(store.updates()).isZero();
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void aCardChangedInTheMeantimeIsReadAgain() {
    store.failNextUpdates(2);

    Card card = handler.execute(COMMAND).block();

    assertThat(store.get(CARD)).isEqualTo(card);
    assertThat(publisher.sent()).hasSize(1);
  }

  @Test
  void aCardThatKeepsChangingGivesUp() {
    store.failNextUpdates(100);

    assertThatThrownBy(() -> handler.execute(COMMAND).block())
        .isInstanceOf(CardConcurrentModificationException.class);
    assertThat(publisher.sent()).isEmpty();
  }

  @Test
  void theLinkIsKeptWhenTheQuestionCannotBeSent() {
    publisher.goDown();

    Card card = handler.execute(COMMAND).block();

    assertThat(store.get(CARD).link(NEW_LINK)).isPresent();
    assertThat(card.pendingLinks()).hasSize(1);
  }
}
