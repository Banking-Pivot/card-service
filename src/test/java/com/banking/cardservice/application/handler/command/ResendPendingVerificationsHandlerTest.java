package com.banking.cardservice.application.handler.command;

import static com.banking.cardservice.support.Cards.id;
import static org.assertj.core.api.Assertions.assertThat;

import com.banking.cardservice.application.model.VerificationRequest;
import com.banking.cardservice.domain.model.AccountLink;
import com.banking.cardservice.domain.model.Card;
import com.banking.cardservice.support.Cards;
import com.banking.cardservice.support.InMemoryCardStore;
import com.banking.cardservice.support.RecordingPublisher;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResendPendingVerificationsHandlerTest {

  private static final UUID OLD = id(1);
  private static final UUID RECENT = id(2);
  private static final UUID ANSWERED = id(3);
  private static final UUID WITH_SECOND = id(4);

  private final InMemoryCardStore store = new InMemoryCardStore();
  private final RecordingPublisher publisher = new RecordingPublisher();

  @Test
  void onlyTheQuestionsThatWaitedTooLongAreAskedAgain() {
    store.put(Cards.pending(OLD, id(11), id(21)));
    store.put(
        Cards.pending(RECENT, id(12), id(22))
            .verificationResent(id(12), Cards.START.plusSeconds(50)));
    store.put(Cards.active(ANSWERED, id(13), id(23)));
    // The second account of an active card waits since second 0 as well.
    store.put(
        Cards.active(WITH_SECOND, id(14), id(24))
            .linkAccount(id(15), id(25), "corr-second", Cards.START));

    Long sent = handlerAt(60, 50).execute().block();

    assertThat(sent).isEqualTo(2);
    assertThat(publisher.sent())
        .containsExactly(
            // The card that still waits to be issued asks about the overdue debt again.
            new VerificationRequest(id(11), id(21), Cards.CUSTOMER, "corr-" + id(11), true),
            new VerificationRequest(id(15), id(25), Cards.CUSTOMER, "corr-second", false));
    AccountLink asked = store.get(OLD).link(id(11)).orElseThrow();
    assertThat(asked.verificationAttempts()).isEqualTo(1);
    assertThat(asked.verificationSentAt()).isEqualTo(Cards.START.plusSeconds(60));
    assertThat(store.get(OLD).updatedAt()).isEqualTo(Cards.START);
    assertThat(store.get(RECENT).link(id(12)).orElseThrow().verificationAttempts()).isEqualTo(1);
    assertThat(store.get(WITH_SECOND).link(id(14)).orElseThrow().verificationAttempts()).isZero();
  }

  @Test
  void aRunLooksAtMostAtItsBatch() {
    store.put(Cards.pending(OLD, id(11), id(21)));
    store.put(Cards.pending(RECENT, id(12), id(22)));

    assertThat(handlerAt(60, 1).execute().block()).isEqualTo(1);
    assertThat(handlerAt(60, 1).execute().block()).isEqualTo(1);
    assertThat(handlerAt(60, 1).execute().block()).isZero();
  }

  @Test
  void aCardThatChangedInTheMeantimeIsLeftForItsAnswer() {
    store.put(Cards.pending(OLD, id(11), id(21)));
    store.failNextUpdates(1);

    assertThat(handlerAt(60, 50).execute().block()).isZero();
    assertThat(publisher.sent()).isEmpty();
    assertThat(store.get(OLD).link(id(11)).orElseThrow().verificationAttempts()).isZero();
  }

  @Test
  void aQuestionThatCannotBeSentIsStillCountedAndTriedOnTheNextRun() {
    store.put(Cards.pending(OLD, id(11), id(21)));
    publisher.goDown();

    assertThat(handlerAt(60, 50).execute().block()).isEqualTo(1);
    assertThat(handlerAt(120, 50).execute().block()).isEqualTo(1);

    Card stored = store.get(OLD);
    assertThat(stored.link(id(11)).orElseThrow().verificationAttempts()).isEqualTo(2);
  }

  @Test
  void nothingWaitsNothingIsSent() {
    store.put(Cards.active(ANSWERED, id(13), id(23)));

    assertThat(handlerAt(600, 50).execute().block()).isZero();
    assertThat(store.updates()).isZero();
  }

  private ResendPendingVerificationsHandler handlerAt(long seconds, int batchSize) {
    return new ResendPendingVerificationsHandler(
        store,
        new VerificationDispatcher(publisher),
        Cards.clockAt(seconds),
        Duration.ofSeconds(30),
        batchSize);
  }
}
