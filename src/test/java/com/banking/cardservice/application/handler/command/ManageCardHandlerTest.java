package com.banking.cardservice.application.handler.command;

import static com.banking.cardservice.support.Cards.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.cardservice.application.exception.CardNotFoundException;
import com.banking.cardservice.domain.exception.CardRuleViolationException;
import com.banking.cardservice.domain.exception.LinkedAccountNotFoundException;
import com.banking.cardservice.domain.exception.RuleViolation;
import com.banking.cardservice.domain.model.Card;
import com.banking.cardservice.domain.model.enums.CardStatus;
import com.banking.cardservice.support.Cards;
import com.banking.cardservice.support.InMemoryCardStore;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManageCardHandlerTest {

  private static final UUID CARD = id(1);
  private static final UUID MAIN_ACCOUNT = id(21);
  private static final UUID SECOND_ACCOUNT = id(22);

  private final InMemoryCardStore store = new InMemoryCardStore();
  private final ManageCardHandler handler =
      new ManageCardHandler(new CardUpdater(store), Cards.clockAt(30));

  @BeforeEach
  void aCardWithTwoAccounts() {
    store.put(Cards.withTwoAccounts(CARD, id(11), MAIN_ACCOUNT, id(12), SECOND_ACCOUNT));
  }

  @Test
  void theMainAccountIsChangedAndStored() {
    Card changed = handler.changeMainAccount(CARD, SECOND_ACCOUNT).block();

    assertThat(changed.mainAccountId()).isEqualTo(SECOND_ACCOUNT);
    assertThat(changed.updatedAt()).isEqualTo(Cards.START.plusSeconds(30));
    assertThat(store.get(CARD)).isEqualTo(changed);
  }

  @Test
  void choosingTheMainAccountAgainWritesNothing() {
    Card same = handler.changeMainAccount(CARD, MAIN_ACCOUNT).block();

    assertThat(same.mainAccountId()).isEqualTo(MAIN_ACCOUNT);
    assertThat(store.updates()).isZero();
  }

  @Test
  void anAccountIsUnlinkedAndStored() {
    Card changed = handler.unlinkAccount(CARD, SECOND_ACCOUNT).block();

    assertThat(changed.accounts()).hasSize(1);
    assertThat(store.get(CARD)).isEqualTo(changed);
    assertThatThrownBy(() -> handler.unlinkAccount(CARD, SECOND_ACCOUNT).block())
        .isInstanceOf(LinkedAccountNotFoundException.class);
  }

  @Test
  void theCardIsBlockedAndUnblocked() {
    assertThat(handler.block(CARD).block().status()).isEqualTo(CardStatus.BLOCKED);
    assertThat(store.get(CARD).status()).isEqualTo(CardStatus.BLOCKED);
    assertThat(handler.block(CARD).block().status()).isEqualTo(CardStatus.BLOCKED);
    assertThat(store.updates()).isEqualTo(1);

    assertThat(handler.unblock(CARD).block().status()).isEqualTo(CardStatus.ACTIVE);
    assertThat(store.get(CARD).status()).isEqualTo(CardStatus.ACTIVE);
    assertThat(store.updates()).isEqualTo(2);
  }

  @Test
  void theCardIsCancelledOnceAndCancellingItAgainWritesNothing() {
    Card cancelled = handler.cancel(CARD).block();

    assertThat(cancelled.status()).isEqualTo(CardStatus.CANCELLED);
    assertThat(cancelled.updatedAt()).isEqualTo(Cards.START.plusSeconds(30));
    assertThat(store.get(CARD)).isEqualTo(cancelled);
    assertThat(handler.cancel(CARD).block()).isEqualTo(cancelled);
    assertThat(store.updates()).isEqualTo(1);
    assertThatThrownBy(() -> handler.block(CARD).block())
        .isInstanceOfSatisfying(
            CardRuleViolationException.class,
            broken -> assertThat(broken.violation()).isEqualTo(RuleViolation.CARD_CANCELLED));
  }

  @Test
  void aCardThatDoesNotExistCannotBeManaged() {
    UUID unknown = id(404);

    assertThatThrownBy(() -> handler.cancel(unknown).block())
        .isInstanceOf(CardNotFoundException.class);

    assertThatThrownBy(() -> handler.block(unknown).block())
        .isInstanceOf(CardNotFoundException.class);
    assertThatThrownBy(() -> handler.unblock(unknown).block())
        .isInstanceOf(CardNotFoundException.class);
    assertThatThrownBy(() -> handler.changeMainAccount(unknown, MAIN_ACCOUNT).block())
        .isInstanceOf(CardNotFoundException.class);
    assertThatThrownBy(() -> handler.unlinkAccount(unknown, MAIN_ACCOUNT).block())
        .isInstanceOf(CardNotFoundException.class);
  }
}
