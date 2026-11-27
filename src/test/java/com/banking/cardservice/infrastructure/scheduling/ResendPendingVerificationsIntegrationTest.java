package com.banking.cardservice.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.cardservice.application.port.in.ResendPendingVerificationsUseCase;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardResponse;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardStatus;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.LinkStatus;
import com.banking.cardservice.support.IntegrationTest;
import com.banking.cardservice.support.TestParticipants.ReceivedMessage;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A question that got no answer is asked again. The tests make a link look old in the database and
 * run the re-send themselves; the timer is set to one hour in the test settings.
 */
class ResendPendingVerificationsIntegrationTest extends IntegrationTest {

  @Autowired private ResendPendingVerificationsUseCase resend;

  private final UUID customer = UUID.randomUUID();

  @Test
  void aQuestionThatGotNoAnswerIsAskedAgainWithTheSameLinkIdentifier() {
    UUID waitingAccount = UUID.randomUUID();
    UUID recentAccount = UUID.randomUUID();
    CardResponse waiting = issued(customer, waitingAccount);
    CardResponse recent = issued(customer, recentAccount);
    UUID waitingLink = linkId(waiting.getId(), waitingAccount);
    UUID recentLink = linkId(recent.getId(), recentAccount);
    ReceivedMessage original = participants.awaitMessage("VERIFY_ACCOUNT", waitingLink);
    participants.awaitMessage("VERIFY_ACCOUNT", recentLink);
    age(waitingLink);

    assertThat(resend.execute().block()).isEqualTo(1);

    ReceivedMessage repeated = participants.awaitMessage("VERIFY_ACCOUNT", waitingLink, 2);
    assertThat(repeated.text()).isEqualTo(original.text());
    assertThat(repeated.key()).isEqualTo(original.key());
    assertThat(repeated.correlationId()).isEqualTo(original.correlationId());
    assertThat(linkColumn(waitingLink, "verification_attempts")).isEqualTo(1);
    assertThat(linkColumn(recentLink, "verification_attempts")).isZero();
    assertThat(participants.countMessages("VERIFY_ACCOUNT", recentLink, Duration.ofSeconds(1)))
        .isEqualTo(1);
    // Sent a moment ago: the next run leaves it alone.
    assertThat(resend.execute().block()).isZero();

    // Account Service answers the first question and the repeat with the same result.
    participants.accountVerified(waitingLink, waitingAccount, customer, "PEN");
    participants.accountVerified(waitingLink, waitingAccount, customer, "PEN");
    awaitStatus(waiting.getId(), CardStatus.ACTIVE);
    age(waitingLink);
    assertThat(resend.execute().block()).isZero();
  }

  @Test
  void theQuestionAboutASecondAccountIsAskedAgainToo() {
    UUID mainAccount = UUID.randomUUID();
    UUID secondAccount = UUID.randomUUID();
    CardResponse card = activeCard(customer, mainAccount);
    link(card.getId(), secondAccount).expectStatus().isAccepted();
    UUID secondLink = linkId(card.getId(), secondAccount);
    participants.awaitMessage("VERIFY_ACCOUNT", secondLink);
    age(secondLink);
    age(linkId(card.getId(), mainAccount));

    assertThat(resend.execute().block()).isEqualTo(1);

    participants.awaitMessage("VERIFY_ACCOUNT", secondLink, 2);
    participants.accountVerified(secondLink, secondAccount, customer, "USD");
    awaitLinkStatus(card.getId(), secondAccount, LinkStatus.LINKED);
  }

  private void age(UUID linkId) {
    databaseClient
        .sql(
            "UPDATE card_accounts SET verification_sent_at = verification_sent_at - INTERVAL '5"
                + " minutes' WHERE id = :id")
        .bind("id", linkId)
        .then()
        .block();
  }
}
