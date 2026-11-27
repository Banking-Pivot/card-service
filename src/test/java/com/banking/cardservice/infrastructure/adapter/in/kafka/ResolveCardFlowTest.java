package com.banking.cardservice.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardResponse;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardStatus;
import com.banking.cardservice.support.IntegrationTest;
import com.banking.cardservice.support.TestParticipants;
import com.banking.cardservice.support.TestParticipants.ReceivedMessage;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** RESOLVE_CARD: which accounts a card uses, answered on the topic of whoever asks. */
class ResolveCardFlowTest extends IntegrationTest {

  private final UUID customer = UUID.randomUUID();
  private final UUID first = UUID.randomUUID();
  private final UUID second = UUID.randomUUID();
  private final UUID third = UUID.randomUUID();

  @Test
  void theAccountsOfAnActiveCardAreAnsweredWithTheMainOneFirstAndTheRestInLinkingOrder() {
    UUID cardId = activeCard(customer, first).getId();
    linked(cardId, customer, second);
    linked(cardId, customer, third);
    // A fourth account still waits for Account Service: the card does not charge it.
    link(cardId, UUID.randomUUID()).expectStatus().isAccepted();
    changeMainAccount(cardId, third).expectStatus().isOk();
    UUID transactionId = UUID.randomUUID();

    participants.resolveCard(transactionId, cardId, null);

    ReceivedMessage reply = participants.awaitMessage("CARD_RESOLVED", transactionId);
    assertThat(reply.topic()).isEqualTo(TestParticipants.TRANSACTION_REPLIES);
    assertThat(reply.key()).isEqualTo(transactionId.toString());
    assertThat(reply.correlationId()).isEqualTo("resolve-" + transactionId);
    assertThat(reply.text())
        .isEqualTo(
            "{\"type\":\"CARD_RESOLVED\",\"transactionId\":\""
                + transactionId
                + "\",\"cardId\":\""
                + cardId
                + "\",\"customerId\":\""
                + customer
                + "\",\"mainAccountId\":\""
                + third
                + "\",\"accountIds\":[\""
                + third
                + "\",\""
                + first
                + "\",\""
                + second
                + "\"]}");
    assertThat(reply.body())
        .containsEntry("accountIds", List.of(third.toString(), first.toString(), second.toString()))
        // Account Service gave no document for this card: the fields are left out, not null.
        .doesNotContainKeys("holderDocumentType", "holderDocumentNumber");
  }

  @Test
  void theAnswerGoesToTheTopicTheCommandNames() {
    UUID cardId = activeCard(customer, first).getId();
    UUID fromYanki = UUID.randomUUID();
    UUID fromTransactions = UUID.randomUUID();

    participants.resolveCard(fromYanki, cardId, TestParticipants.YANKI_REPLIES);
    participants.resolveCard(fromTransactions, cardId, TestParticipants.TRANSACTION_REPLIES);

    ReceivedMessage toYanki = participants.awaitMessage("CARD_RESOLVED", fromYanki);
    assertThat(toYanki.topic()).isEqualTo(TestParticipants.YANKI_REPLIES);
    assertThat(toYanki.field("mainAccountId")).isEqualTo(first.toString());
    assertThat(toYanki.field("customerId")).isEqualTo(customer.toString());
    assertThat(participants.awaitMessage("CARD_RESOLVED", fromTransactions).topic())
        .isEqualTo(TestParticipants.TRANSACTION_REPLIES);
    assertThat(participants.countMessages(fromYanki, Duration.ofSeconds(1))).isEqualTo(1);
  }

  @Test
  void aCardThatDoesNotExistIsRefused() {
    UUID cardId = UUID.randomUUID();
    UUID transactionId = UUID.randomUUID();

    participants.resolveCard(transactionId, cardId, null);

    ReceivedMessage reply = participants.awaitMessage("CARD_RESOLUTION_REJECTED", transactionId);
    assertThat(reply.topic()).isEqualTo(TestParticipants.TRANSACTION_REPLIES);
    assertThat(reply.key()).isEqualTo(transactionId.toString());
    assertThat(reply.text())
        .isEqualTo(
            "{\"type\":\"CARD_RESOLUTION_REJECTED\",\"transactionId\":\""
                + transactionId
                + "\",\"cardId\":\""
                + cardId
                + "\",\"code\":\"CARD_NOT_FOUND\",\"message\":\"The card does not exist.\"}");
  }

  @Test
  void aCardThatIsNotActiveIsRefused() {
    CardResponse pending = issued(customer, first);
    UUID blockedId = activeCard(customer, second).getId();
    block(blockedId).expectStatus().isOk();
    UUID aboutPending = UUID.randomUUID();
    UUID aboutBlocked = UUID.randomUUID();

    participants.resolveCard(aboutPending, pending.getId(), null);
    participants.resolveCard(aboutBlocked, blockedId, TestParticipants.YANKI_REPLIES);

    ReceivedMessage refused = participants.awaitMessage("CARD_RESOLUTION_REJECTED", aboutPending);
    assertThat(refused.field("code")).isEqualTo("CARD_NOT_ACTIVE");
    assertThat(refused.field("message")).isEqualTo("The card is not active.");
    assertThat(refused.body()).doesNotContainKeys("customerId", "mainAccountId", "accountIds");
    ReceivedMessage blocked = participants.awaitMessage("CARD_RESOLUTION_REJECTED", aboutBlocked);
    assertThat(blocked.topic()).isEqualTo(TestParticipants.YANKI_REPLIES);
    assertThat(blocked.field("code")).isEqualTo("CARD_NOT_ACTIVE");
    assertThat(blocked.field("cardId")).isEqualTo(blockedId.toString());
  }

  @Test
  void aCancelledCardIsRefusedWithItsOwnCode() {
    UUID cardId = activeCard(customer, first).getId();
    webTestClient
        .delete()
        .uri("/api/v1/cards/{cardId}", cardId)
        .exchange()
        .expectStatus()
        .isNoContent();
    UUID transactionId = UUID.randomUUID();

    participants.resolveCard(transactionId, cardId, null);

    ReceivedMessage reply = participants.awaitMessage("CARD_RESOLUTION_REJECTED", transactionId);
    assertThat(reply.text())
        .isEqualTo(
            "{\"type\":\"CARD_RESOLUTION_REJECTED\",\"transactionId\":\""
                + transactionId
                + "\",\"cardId\":\""
                + cardId
                + "\",\"code\":\"CARD_CANCELLED\",\"message\":\"The card is cancelled.\"}");
  }

  @Test
  void aCommandThatNamesATopicOutsideTheListIsNotAnswered() {
    UUID cardId = activeCard(customer, first).getId();
    UUID toAccounts = UUID.randomUUID();
    UUID toNowhere = UUID.randomUUID();
    UUID allowed = UUID.randomUUID();

    // Same card, same partition: the allowed one is handled after the other two.
    participants.resolveCard(toAccounts, cardId, TestParticipants.ACCOUNT_COMMANDS);
    participants.resolveCard(toNowhere, cardId, "banking.unknown.replies");
    participants.resolveCard(allowed, cardId, null);

    participants.awaitMessage("CARD_RESOLVED", allowed);
    assertThat(participants.countMessages(toAccounts, Duration.ofSeconds(1))).isZero();
    assertThat(participants.countMessages(toNowhere, Duration.ZERO)).isZero();
  }

  @Test
  void messagesThatAreNotCommandsAreSkippedAndTheNextOneIsAnswered() {
    UUID cardId = activeCard(customer, first).getId();
    UUID ignored = UUID.randomUUID();
    UUID answered = UUID.randomUUID();
    String key = cardId.toString();
    String ids = "\"transactionId\":\"" + ignored + "\",\"cardId\":\"" + cardId + "\"";

    for (String unreadable :
        new String[] {
          "not json",
          "{" + ids + "}",
          "{\"type\":\"VERIFY_ACCOUNT\"," + ids + "}",
          "{\"type\":\"RESOLVE_CARD\",\"cardId\":\"" + cardId + "\"}",
          "{\"type\":\"RESOLVE_CARD\",\"transactionId\":\"" + ignored + "\"}",
          "{\"type\":\"RESOLVE_CARD\",\"transactionId\":\"" + ignored + "\",\"cardId\":\"x\"}"
        }) {
      participants.send(TestParticipants.CARD_COMMANDS, key, unreadable, "broken");
    }
    participants.send(TestParticipants.CARD_COMMANDS, key, null, null);
    participants.resolveCard(answered, cardId, null);

    assertThat(participants.awaitMessage("CARD_RESOLVED", answered).correlationId())
        .isEqualTo("resolve-" + answered);
    assertThat(participants.countMessages(ignored, Duration.ofMillis(500))).isZero();
  }

  @Test
  void theDocumentOfTheHolderTravelsWithTheAnswerWhenTheCardKnowsIt() {
    CardResponse card = issued(customer, first);
    participants.accountVerified(
        linkId(card.getId(), first), first, customer, "PEN", "DNI", "45871236");
    awaitStatus(card.getId(), CardStatus.ACTIVE);
    UUID transactionId = UUID.randomUUID();

    participants.resolveCard(transactionId, card.getId(), TestParticipants.YANKI_REPLIES);

    ReceivedMessage reply = participants.awaitMessage("CARD_RESOLVED", transactionId);
    assertThat(reply.topic()).isEqualTo(TestParticipants.YANKI_REPLIES);
    assertThat(reply.text())
        .isEqualTo(
            "{\"type\":\"CARD_RESOLVED\",\"transactionId\":\""
                + transactionId
                + "\",\"cardId\":\""
                + card.getId()
                + "\",\"customerId\":\""
                + customer
                + "\",\"mainAccountId\":\""
                + first
                + "\",\"accountIds\":[\""
                + first
                + "\"],\"holderDocumentType\":\"DNI\",\"holderDocumentNumber\":\"45871236\"}");
  }
}
