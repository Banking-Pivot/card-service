package com.banking.cardservice.application.handler.command;

import static com.banking.cardservice.support.Cards.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.cardservice.application.exception.CardConcurrentModificationException;
import com.banking.cardservice.application.model.AccountVerification;
import com.banking.cardservice.domain.model.Card;
import com.banking.cardservice.domain.model.CardStatus;
import com.banking.cardservice.domain.model.HolderDocument;
import com.banking.cardservice.domain.model.LinkStatus;
import com.banking.cardservice.domain.model.Rejection;
import com.banking.cardservice.support.Cards;
import com.banking.cardservice.support.InMemoryCardStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApplyAccountVerificationHandlerTest {

  private static final UUID CARD = id(1);
  private static final UUID LINK = id(11);
  private static final UUID ACCOUNT = id(21);

  private final InMemoryCardStore store = new InMemoryCardStore();
  private final ApplyAccountVerificationHandler handler =
      new ApplyAccountVerificationHandler(store, Cards.clockAt(5));

  @Test
  void aConfirmedMainAccountActivatesTheCard() {
    store.put(Cards.pending(CARD, LINK, ACCOUNT));

    handler.execute(new AccountVerification.Verified(LINK, "PEN")).block();

    Card stored = store.get(CARD);
    assertThat(stored.status()).isEqualTo(CardStatus.ACTIVE);
    assertThat(stored.link(LINK).orElseThrow().currency()).isEqualTo("PEN");
    assertThat(stored.updatedAt()).isEqualTo(Cards.START.plusSeconds(5));
  }

  @Test
  void aRefusedMainAccountRejectsTheCardWithTheReasonOfAccountService() {
    store.put(Cards.pending(CARD, LINK, ACCOUNT));

    handler
        .execute(new AccountVerification.Refused(LINK, "ACCOUNT_NOT_OWNED", "Not a holder."))
        .block();

    Card stored = store.get(CARD);
    assertThat(stored.status()).isEqualTo(CardStatus.REJECTED);
    assertThat(stored.rejection()).isEqualTo(new Rejection("ACCOUNT_NOT_OWNED", "Not a holder."));
    assertThat(stored.link(LINK).orElseThrow().status()).isEqualTo(LinkStatus.REJECTED);
  }

  @Test
  void anAnswerThatArrivesAgainOrForAnUnknownLinkChangesNothing() {
    store.put(Cards.active(CARD, LINK, ACCOUNT));

    handler.execute(new AccountVerification.Verified(LINK, "USD")).block();
    handler.execute(new AccountVerification.Refused(LINK, "ACCOUNT_NOT_FOUND", "Gone.")).block();
    handler.execute(new AccountVerification.Verified(id(99), "PEN")).block();

    assertThat(store.updates()).isZero();
    assertThat(store.get(CARD).link(LINK).orElseThrow().currency()).isEqualTo("PEN");
  }

  @Test
  void aCardChangedInTheMeantimeIsReadAgainAndTheAnswerStillApplies() {
    store.put(Cards.pending(CARD, LINK, ACCOUNT));
    store.failNextUpdates(3);

    handler.execute(new AccountVerification.Verified(LINK, "PEN")).block();

    assertThat(store.get(CARD).status()).isEqualTo(CardStatus.ACTIVE);
  }

  @Test
  void anAnswerThatCannotBeStoredFailsSoItIsHandledAgain() {
    store.put(Cards.pending(CARD, LINK, ACCOUNT));
    store.failNextUpdates(100);

    assertThatThrownBy(() -> handler.execute(new AccountVerification.Verified(LINK, "PEN")).block())
        .isInstanceOf(CardConcurrentModificationException.class);
    assertThat(store.get(CARD).status()).isEqualTo(CardStatus.PENDING_VERIFICATION);
  }

  @Test
  void anAnswerWithoutWhatItNeedsIsNotAnAnswer() {
    assertThatThrownBy(() -> new AccountVerification.Verified(LINK, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new AccountVerification.Verified(null, "PEN"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new AccountVerification.Refused(LINK, null, "message"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new AccountVerification.Refused(LINK, "CODE", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void theDocumentOfTheHolderIsStoredWithTheConfirmationOfTheFirstAccount() {
    store.put(Cards.pending(CARD, LINK, ACCOUNT));
    HolderDocument document = new HolderDocument("DNI", "45871236");

    handler.execute(new AccountVerification.Verified(LINK, "PEN", document)).block();

    assertThat(store.get(CARD).status()).isEqualTo(CardStatus.ACTIVE);
    assertThat(store.get(CARD).holderDocument()).isEqualTo(document);
    assertThat(new AccountVerification.Verified(LINK, "PEN").holderDocument()).isNull();
  }

  @Test
  void aCardRefusedForOverdueDebtOrAnInactiveCustomerIsRejectedWithThatReason() {
    for (String code : new String[] {"OVERDUE_DEBT", "CUSTOMER_INACTIVE"}) {
      store.put(Cards.pending(CARD, LINK, ACCOUNT));

      handler.execute(new AccountVerification.Refused(LINK, code, "Refused.")).block();

      Card stored = store.get(CARD);
      assertThat(stored.status()).isEqualTo(CardStatus.REJECTED);
      assertThat(stored.rejection()).isEqualTo(new Rejection(code, "Refused."));
      assertThat(stored.link(LINK).orElseThrow().status()).isEqualTo(LinkStatus.REJECTED);
    }
  }
}
