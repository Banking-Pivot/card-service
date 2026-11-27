package com.banking.cardservice.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.banking.cardservice.infrastructure.config.CardProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class PendingVerificationSchedulerTest {

  private static final CardProperties EVERY_20_MS =
      new CardProperties(
          new CardProperties.Messaging(
              "commands",
              "replies",
              "account",
              "transaction",
              List.of("transaction", "yanki"),
              Duration.ofSeconds(1),
              Duration.ofSeconds(1),
              Duration.ofMillis(20),
              Duration.ofSeconds(30),
              50,
              Duration.ofSeconds(30)),
          new CardProperties.Limits(3));

  @Test
  void theReSendRunsAgainAndAgainUntilTheServiceStops() {
    AtomicInteger runs = new AtomicInteger();
    PendingVerificationScheduler scheduler =
        new PendingVerificationScheduler(
            () -> Mono.fromSupplier(() -> (long) runs.incrementAndGet()), EVERY_20_MS);

    scheduler.start();
    scheduler.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() >= 3);
    scheduler.stop();
    scheduler.stop();
    int afterStop = runs.get();

    await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(() -> true);
    assertThat(runs.get()).isLessThanOrEqualTo(afterStop + 1);
  }

  @Test
  void aRunThatFailsDoesNotStopTheNextOnes() {
    AtomicInteger runs = new AtomicInteger();
    PendingVerificationScheduler scheduler =
        new PendingVerificationScheduler(
            () ->
                runs.incrementAndGet() == 1
                    ? Mono.error(new IllegalStateException("database down"))
                    : Mono.just(0L),
            EVERY_20_MS);

    scheduler.start();
    try {
      await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() >= 3);
    } finally {
      scheduler.stop();
    }
  }

  @Test
  void onlyTheTopicsOfTheListMayReceiveAnAnswer() {
    CardProperties.Messaging messaging = EVERY_20_MS.messaging();

    assertThat(messaging.allowsReplyTo("transaction")).isTrue();
    assertThat(messaging.allowsReplyTo("yanki")).isTrue();
    assertThat(messaging.allowsReplyTo("account")).isFalse();
  }
}
