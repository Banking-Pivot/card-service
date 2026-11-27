package com.banking.cardservice.domain.model;

import static com.banking.cardservice.support.Cards.START;
import static com.banking.cardservice.support.Cards.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.cardservice.domain.exception.CardRuleViolationException;
import com.banking.cardservice.domain.exception.LinkedAccountNotFoundException;
import com.banking.cardservice.domain.exception.RuleViolation;
import com.banking.cardservice.support.Cards;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class CardTest {

  private static final UUID CARD = id(1);
  private static final UUID MAIN_LINK = id(11);
  private static final UUID SECOND_LINK = id(12);
  private static final UUID THIRD_LINK = id(13);
  private static final UUID MAIN_ACCOUNT = id(21);
  private static final UUID SECOND_ACCOUNT = id(22);
  private static final UUID THIRD_ACCOUNT = id(23);
  private static final Instant LATER = START.plusSeconds(60);

  @Test
  void aNewCardWaitsForTheAnswerAboutItsMainAccount() {
    Card card = Cards.pending(CARD, MAIN_LINK, MAIN_ACCOUNT);

    assertThat(card.status()).isEqualTo(CardStatus.PENDING_VERIFICATION);
    assertThat(card.version()).isZero();
    assertThat(card.mainAccountId()).isEqualTo(MAIN_ACCOUNT);
    assertThat(card.chargeableAccountIds()).isEmpty();
    assertThat(card.pendingLinks()).hasSize(1);
    AccountLink link = card.link(MAIN_LINK).orElseThrow();
    assertThat(link.position()).isEqualTo(1);
    assertThat(link.main()).isTrue();
    assertThat(link.isPending()).isTrue();
    assertThat(link.correlationId()).isEqualTo("corr-" + MAIN_LINK);
    assertThat(link.verificationSentAt()).isEqualTo(START);
    assertThat(link.verificationAttempts()).isZero();
  }

  @Test
  void theCardBecomesActiveWhenItsMainAccountIsConfirmed() {
    Card pending = Cards.pending(CARD, MAIN_LINK, MAIN_ACCOUNT);

    Card active = pending.accountVerified(MAIN_LINK, "PEN", LATER).orElseThrow();

    assertThat(active.status()).isEqualTo(CardStatus.ACTIVE);
    assertThat(active.rejection()).isNull();
    assertThat(active.version()).isEqualTo(1);
    assertThat(active.updatedAt()).isEqualTo(LATER);
    assertThat(active.createdAt()).isEqualTo(START);
    AccountLink link = active.link(MAIN_LINK).orElseThrow();
    assertThat(link.isLinked()).isTrue();
    assertThat(link.currency()).isEqualTo("PEN");
    assertThat(link.updatedAt()).isEqualTo(LATER);
    assertThat(active.chargeableAccountIds()).containsExactly(MAIN_ACCOUNT);
    assertThat(active.pendingLinks()).isEmpty();
  }

  @Test
  void theCardIsRejectedWhenItsMainAccountIsRefused() {
    Rejection reason = new Rejection("ACCOUNT_NOT_OWNED", "The customer does not hold it.");

    Card rejected =
        Cards.pending(CARD, MAIN_LINK, MAIN_ACCOUNT)
            .accountRejected(MAIN_LINK, reason, LATER)
            .orElseThrow();

    assertThat(rejected.status()).isEqualTo(CardStatus.REJECTED);
    assertThat(rejected.rejection()).isEqualTo(reason);
    assertThat(rejected.link(MAIN_LINK).orElseThrow().status()).isEqualTo(LinkStatus.REJECTED);
    assertThat(rejected.link(MAIN_LINK).orElseThrow().rejection()).isEqualTo(reason);
    assertThat(rejected.chargeableAccountIds()).isEmpty();
    assertViolation(
        () -> rejected.linkAccount(SECOND_LINK, SECOND_ACCOUNT, "corr", LATER),
        RuleViolation.CARD_NOT_ACTIVE);
  }

  @Test
  void anAnswerIsAppliedOnlyWhileItsLinkWaits() {
    Card active = Cards.active(CARD, MAIN_LINK, MAIN_ACCOUNT);
    Rejection reason = new Rejection("ACCOUNT_NOT_FOUND", "The account does not exist.");

    assertThat(active.accountVerified(MAIN_LINK, "USD", LATER)).isEmpty();
    assertThat(active.accountRejected(MAIN_LINK, reason, LATER)).isEmpty();
    assertThat(active.accountVerified(id(99), "PEN", LATER)).isEmpty();
    assertThat(active.accountRejected(id(99), reason, LATER)).isEmpty();
  }

  @Test
  void anAccountIsLinkedAfterTheOnesTheCardAlreadyHas() {
    Card active = Cards.active(CARD, MAIN_LINK, MAIN_ACCOUNT);

    Card asked = active.linkAccount(SECOND_LINK, SECOND_ACCOUNT, "corr-2", LATER);

    AccountLink link = asked.link(SECOND_LINK).orElseThrow();
    assertThat(link.position()).isEqualTo(2);
    assertThat(link.main()).isFalse();
    assertThat(link.isPending()).isTrue();
    assertThat(link.correlationId()).isEqualTo("corr-2");
    assertThat(asked.version()).isEqualTo(active.version() + 1);
    assertThat(asked.status()).isEqualTo(CardStatus.ACTIVE);
    assertThat(asked.chargeableAccountIds()).containsExactly(MAIN_ACCOUNT);

    Card linked = asked.accountVerified(SECOND_LINK, "USD", LATER.plusSeconds(1)).orElseThrow();
    assertThat(linked.status()).isEqualTo(CardStatus.ACTIVE);
    assertThat(linked.mainAccountId()).isEqualTo(MAIN_ACCOUNT);
    assertThat(linked.chargeableAccountIds()).containsExactly(MAIN_ACCOUNT, SECOND_ACCOUNT);
  }

  @Test
  void anAccountCannotBeLinkedTwiceNorToACardThatIsNotActive() {
    Card pending = Cards.pending(CARD, MAIN_LINK, MAIN_ACCOUNT);
    Card active = Cards.active(CARD, MAIN_LINK, MAIN_ACCOUNT);
    Card waiting = active.linkAccount(SECOND_LINK, SECOND_ACCOUNT, "corr", LATER);

    assertViolation(
        () -> pending.linkAccount(SECOND_LINK, SECOND_ACCOUNT, "corr", LATER),
        RuleViolation.CARD_NOT_ACTIVE);
    assertViolation(
        () -> active.linkAccount(SECOND_LINK, MAIN_ACCOUNT, "corr", LATER),
        RuleViolation.ACCOUNT_ALREADY_LINKED);
    assertViolation(
        () -> waiting.linkAccount(THIRD_LINK, SECOND_ACCOUNT, "corr", LATER),
        RuleViolation.ACCOUNT_ALREADY_LINKED);
  }

  @Test
  void aRefusedSecondAccountLeavesTheCardActiveAndCanBeTriedAgain() {
    Rejection reason = new Rejection("ACCOUNT_NOT_FOUND", "The account does not exist.");
    Card refused =
        Cards.active(CARD, MAIN_LINK, MAIN_ACCOUNT)
            .linkAccount(SECOND_LINK, SECOND_ACCOUNT, "corr", LATER)
            .accountRejected(SECOND_LINK, reason, LATER)
            .orElseThrow();

    assertThat(refused.status()).isEqualTo(CardStatus.ACTIVE);
    assertThat(refused.rejection()).isNull();
    assertThat(refused.link(SECOND_LINK).orElseThrow().rejection()).isEqualTo(reason);
    assertThat(refused.chargeableAccountIds()).containsExactly(MAIN_ACCOUNT);

    Card again = refused.linkAccount(THIRD_LINK, SECOND_ACCOUNT, "corr-again", LATER);

    assertThat(again.accounts()).hasSize(2);
    assertThat(again.link(SECOND_LINK)).isEmpty();
    AccountLink retry = again.link(THIRD_LINK).orElseThrow();
    assertThat(retry.isPending()).isTrue();
    assertThat(retry.position()).isEqualTo(2);
    assertThat(retry.accountId()).isEqualTo(SECOND_ACCOUNT);
  }

  @Test
  void theMainAccountChangesToAnotherLinkedAccountAndIsChargedFirst() {
    Card card =
        Cards.withTwoAccounts(CARD, MAIN_LINK, MAIN_ACCOUNT, SECOND_LINK, SECOND_ACCOUNT)
            .linkAccount(THIRD_LINK, THIRD_ACCOUNT, "corr", LATER)
            .accountVerified(THIRD_LINK, "PEN", LATER)
            .orElseThrow();

    Card changed = card.changeMainAccount(THIRD_ACCOUNT, LATER.plusSeconds(5));

    assertThat(changed.mainAccountId()).isEqualTo(THIRD_ACCOUNT);
    assertThat(changed.accounts()).filteredOn(AccountLink::main).hasSize(1);
    assertThat(changed.chargeableAccountIds())
        .containsExactly(THIRD_ACCOUNT, MAIN_ACCOUNT, SECOND_ACCOUNT);
    assertThat(changed.accounts()).extracting(AccountLink::position).containsExactly(1, 2, 3);
    assertThat(changed.version()).isEqualTo(card.version() + 1);
    assertThat(changed.link(MAIN_LINK).orElseThrow().updatedAt()).isEqualTo(LATER.plusSeconds(5));
    assertThat(changed.link(SECOND_LINK).orElseThrow().updatedAt())
        .isEqualTo(card.link(SECOND_LINK).orElseThrow().updatedAt());
    assertThat(changed.changeMainAccount(THIRD_ACCOUNT, LATER.plusSeconds(9))).isSameAs(changed);
  }

  @Test
  void onlyALinkedAccountOfAnActiveCardCanBecomeTheMainOne() {
    Card active = Cards.active(CARD, MAIN_LINK, MAIN_ACCOUNT);
    Card waiting = active.linkAccount(SECOND_LINK, SECOND_ACCOUNT, "corr", LATER);
    Card blocked =
        Cards.withTwoAccounts(CARD, MAIN_LINK, MAIN_ACCOUNT, SECOND_LINK, SECOND_ACCOUNT)
            .block(LATER);

    assertViolation(
        () -> active.changeMainAccount(SECOND_ACCOUNT, LATER), RuleViolation.ACCOUNT_NOT_LINKED);
    assertViolation(
        () -> waiting.changeMainAccount(SECOND_ACCOUNT, LATER), RuleViolation.ACCOUNT_NOT_LINKED);
    assertViolation(
        () -> blocked.changeMainAccount(SECOND_ACCOUNT, LATER), RuleViolation.CARD_NOT_ACTIVE);
  }

  @Test
  void anAccountIsUnlinkedUnlessItIsTheMainOne() {
    Card card = Cards.withTwoAccounts(CARD, MAIN_LINK, MAIN_ACCOUNT, SECOND_LINK, SECOND_ACCOUNT);

    Card unlinked = card.unlinkAccount(SECOND_ACCOUNT, LATER);

    assertThat(unlinked.accounts())
        .extracting(AccountLink::accountId)
        .containsExactly(MAIN_ACCOUNT);
    assertThat(unlinked.version()).isEqualTo(card.version() + 1);
    assertThat(unlinked.updatedAt()).isEqualTo(LATER);
    assertViolation(
        () -> card.unlinkAccount(MAIN_ACCOUNT, LATER), RuleViolation.MAIN_ACCOUNT_REQUIRED);
    assertThatThrownBy(() -> card.unlinkAccount(THIRD_ACCOUNT, LATER))
        .isInstanceOf(LinkedAccountNotFoundException.class)
        .hasMessageContaining(THIRD_ACCOUNT.toString());
    assertViolation(
        () -> card.block(LATER).unlinkAccount(SECOND_ACCOUNT, LATER),
        RuleViolation.CARD_NOT_ACTIVE);
  }

  @Test
  void anAccountThatStillWaitsCanBeUnlinkedAndItsLateAnswerFindsNothing() {
    Card waiting =
        Cards.active(CARD, MAIN_LINK, MAIN_ACCOUNT)
            .linkAccount(SECOND_LINK, SECOND_ACCOUNT, "corr", LATER);

    Card unlinked = waiting.unlinkAccount(SECOND_ACCOUNT, LATER);

    assertThat(unlinked.pendingLinks()).isEmpty();
    assertThat(unlinked.accountVerified(SECOND_LINK, "USD", LATER)).isEmpty();
  }

  @Test
  void aCardIsBlockedAndUnblockedAndRepeatingEitherChangesNothing() {
    Card active = Cards.active(CARD, MAIN_LINK, MAIN_ACCOUNT);

    Card blocked = active.block(LATER);

    assertThat(blocked.status()).isEqualTo(CardStatus.BLOCKED);
    assertThat(blocked.version()).isEqualTo(active.version() + 1);
    assertThat(blocked.block(LATER.plusSeconds(1))).isSameAs(blocked);
    assertThat(active.unblock(LATER)).isSameAs(active);

    Card unblocked = blocked.unblock(LATER.plusSeconds(2));

    assertThat(unblocked.status()).isEqualTo(CardStatus.ACTIVE);
    assertThat(unblocked.updatedAt()).isEqualTo(LATER.plusSeconds(2));
    assertThat(unblocked.chargeableAccountIds()).containsExactly(MAIN_ACCOUNT);
  }

  @Test
  void aCardThatNeverBecameActiveCannotBeBlockedNorUnblocked() {
    Card pending = Cards.pending(CARD, MAIN_LINK, MAIN_ACCOUNT);
    Card rejected =
        pending
            .accountRejected(MAIN_LINK, new Rejection("ACCOUNT_NOT_FOUND", "Unknown."), LATER)
            .orElseThrow();

    assertViolation(() -> pending.block(LATER), RuleViolation.CARD_NOT_ACTIVE);
    assertViolation(() -> pending.unblock(LATER), RuleViolation.CARD_NOT_ACTIVE);
    assertViolation(() -> rejected.block(LATER), RuleViolation.CARD_NOT_ACTIVE);
    assertViolation(() -> rejected.unblock(LATER), RuleViolation.CARD_NOT_ACTIVE);
  }

  @Test
  void anAccountOfABlockedCardIsStillConfirmedWhenItsAnswerArrives() {
    Card blocked =
        Cards.active(CARD, MAIN_LINK, MAIN_ACCOUNT)
            .linkAccount(SECOND_LINK, SECOND_ACCOUNT, "corr", LATER)
            .block(LATER);

    Card answered = blocked.accountVerified(SECOND_LINK, "USD", LATER).orElseThrow();

    assertThat(answered.status()).isEqualTo(CardStatus.BLOCKED);
    assertThat(answered.link(SECOND_LINK).orElseThrow().isLinked()).isTrue();
  }

  @Test
  void aQuestionSentAgainCountsOneMoreAttemptAndKeepsTheRestOfTheCard() {
    Card pending = Cards.pending(CARD, MAIN_LINK, MAIN_ACCOUNT);

    Card resent = pending.verificationResent(MAIN_LINK, LATER);

    AccountLink link = resent.link(MAIN_LINK).orElseThrow();
    assertThat(link.verificationAttempts()).isEqualTo(1);
    assertThat(link.verificationSentAt()).isEqualTo(LATER);
    assertThat(link.updatedAt()).isEqualTo(START);
    assertThat(resent.updatedAt()).isEqualTo(START);
    assertThat(resent.version()).isEqualTo(1);
    assertThat(resent.status()).isEqualTo(CardStatus.PENDING_VERIFICATION);
    assertThat(pending.verificationResent(id(99), LATER)).isSameAs(pending);
    Card active = Cards.active(CARD, MAIN_LINK, MAIN_ACCOUNT);
    assertThat(active.verificationResent(MAIN_LINK, LATER)).isSameAs(active);
  }

  @Test
  void theAccountsOfACardAreKeptInLinkingOrder() {
    Card card = Cards.withTwoAccounts(CARD, MAIN_LINK, MAIN_ACCOUNT, SECOND_LINK, SECOND_ACCOUNT);
    List<AccountLink> reversed = List.of(card.accounts().get(1), card.accounts().get(0));

    Card rebuilt =
        new Card(
            card.id(),
            card.customerId(),
            card.number(),
            card.status(),
            null,
            null,
            reversed,
            card.createdAt(),
            card.updatedAt(),
            card.version());

    assertThat(rebuilt).isEqualTo(card);
  }

  @Test
  void aCardWithoutAMainAccountIsABrokenCard() {
    Card broken =
        new Card(
            CARD,
            Cards.CUSTOMER,
            Cards.NUMBER,
            CardStatus.ACTIVE,
            null,
            null,
            List.of(),
            START,
            START,
            0);

    assertThatThrownBy(broken::mainAccountId).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new Card(
                    null,
                    Cards.CUSTOMER,
                    Cards.NUMBER,
                    CardStatus.ACTIVE,
                    null,
                    null,
                    List.of(),
                    START,
                    START,
                    0))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void theDocumentOfTheHolderIsKeptFromTheAnswerAboutTheFirstAccount() {
    HolderDocument document = new HolderDocument("DNI", "45871236");
    Card pending = Cards.pending(CARD, MAIN_LINK, MAIN_ACCOUNT);
    assertThat(pending.holderDocument()).isNull();

    Card active = pending.accountVerified(MAIN_LINK, "PEN", document, LATER).orElseThrow();

    assertThat(active.status()).isEqualTo(CardStatus.ACTIVE);
    assertThat(active.holderDocument()).isEqualTo(document);
    // Every later change keeps it.
    Card changed =
        active
            .linkAccount(SECOND_LINK, SECOND_ACCOUNT, "corr-2", LATER)
            .accountVerified(SECOND_LINK, "USD", new HolderDocument("CE", "X1"), LATER)
            .orElseThrow()
            .changeMainAccount(SECOND_ACCOUNT, LATER)
            .block(LATER)
            .unblock(LATER)
            .linkAccount(THIRD_LINK, THIRD_ACCOUNT, "corr-3", LATER)
            .verificationResent(THIRD_LINK, LATER)
            .accountRejected(THIRD_LINK, new Rejection("ACCOUNT_NOT_FOUND", "Unknown."), LATER)
            .orElseThrow();
    assertThat(changed.holderDocument()).isEqualTo(document);
  }

  @Test
  void aCardWhoseAnswerBringsNoDocumentHasNone() {
    Card pending = Cards.pending(CARD, MAIN_LINK, MAIN_ACCOUNT);

    assertThat(
            pending.accountVerified(MAIN_LINK, "PEN", null, LATER).orElseThrow().holderDocument())
        .isNull();
    assertThat(pending.accountVerified(MAIN_LINK, "PEN", LATER).orElseThrow().holderDocument())
        .isNull();
    assertThat(pending.accountVerified(id(99), "PEN", new HolderDocument("DNI", "1"), LATER))
        .isEmpty();
  }

  @Test
  void onlyTheFirstAccountOfACardThatWaitsToBeIssuedIsItsIssuance() {
    Card pending = Cards.pending(CARD, MAIN_LINK, MAIN_ACCOUNT);
    Card active = Cards.active(CARD, MAIN_LINK, MAIN_ACCOUNT);
    Card asked = active.linkAccount(SECOND_LINK, SECOND_ACCOUNT, "corr-2", LATER);

    assertThat(pending.isIssuance(MAIN_LINK)).isTrue();
    assertThat(pending.isIssuance(id(99))).isFalse();
    assertThat(active.isIssuance(MAIN_LINK)).isFalse();
    assertThat(asked.isIssuance(SECOND_LINK)).isFalse();
  }

  private static void assertViolation(ThrowingCallable action, RuleViolation expected) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            CardRuleViolationException.class,
            broken -> assertThat(broken.violation()).isEqualTo(expected));
  }
}
