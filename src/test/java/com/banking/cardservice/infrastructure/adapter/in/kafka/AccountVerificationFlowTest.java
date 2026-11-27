package com.banking.cardservice.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardResponse;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardStatus;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.LinkStatus;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.LinkedAccount;
import com.banking.cardservice.infrastructure.correlation.CorrelationId;
import com.banking.cardservice.support.IntegrationTest;
import com.banking.cardservice.support.TestParticipants;
import com.banking.cardservice.support.TestParticipants.ReceivedMessage;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/** The check with Account Service that an account belongs to the customer of the card. */
class AccountVerificationFlowTest extends IntegrationTest {

  private final UUID customer = UUID.randomUUID();
  private final UUID mainAccount = UUID.randomUUID();
  private final UUID secondAccount = UUID.randomUUID();

  @Test
  void aNewCardAsksAccountServiceAndBecomesActiveWhenTheAccountIsConfirmed() {
    EntityExchangeResult<CardResponse> accepted =
        issue(customer, mainAccount)
            .expectStatus()
            .isAccepted()
            .expectBody(CardResponse.class)
            .returnResult();
    UUID cardId = accepted.getResponseBody().getId();
    UUID linkId = linkId(cardId, mainAccount);

    ReceivedMessage question = participants.awaitMessage("VERIFY_ACCOUNT", linkId);

    assertThat(question.topic()).isEqualTo(TestParticipants.ACCOUNT_COMMANDS);
    assertThat(question.key()).isEqualTo(mainAccount.toString());
    assertThat(question.body())
        .containsOnlyKeys(
            "type", "transactionId", "accountId", "customerId", "replyTo", "requireNoOverdueDebt");
    // Issuing a card is acquiring a product: Account Service must refuse a customer in arrears.
    assertThat(question.text())
        .isEqualTo(
            "{\"type\":\"VERIFY_ACCOUNT\",\"transactionId\":\""
                + linkId
                + "\",\"accountId\":\""
                + mainAccount
                + "\",\"customerId\":\""
                + customer
                + "\",\"replyTo\":\"banking.card.replies\",\"requireNoOverdueDebt\":true}");
    assertThat(question.field("accountId")).isEqualTo(mainAccount.toString());
    assertThat(question.field("customerId")).isEqualTo(customer.toString());
    assertThat(question.field("replyTo")).isEqualTo(TestParticipants.CARD_REPLIES);
    assertThat(question.correlationId())
        .isEqualTo(accepted.getResponseHeaders().getFirst(CorrelationId.HEADER));

    participants.accountVerified(linkId, mainAccount, customer, "PEN");

    CardResponse active = awaitStatus(cardId, CardStatus.ACTIVE);
    LinkedAccount main = account(active, mainAccount);
    assertThat(main.getStatus()).isEqualTo(LinkStatus.LINKED);
    assertThat(main.getCurrency()).isEqualTo("PEN");
    assertThat(main.getMain()).isTrue();
    assertThat(active.getRejection()).isNull();
    assertThat(active.getUpdatedAt()).isAfterOrEqualTo(active.getCreatedAt());
  }

  @Test
  void aCardOnAnAccountOfAnotherCustomerIsRejected() {
    CardResponse card = issued(customer, mainAccount);
    UUID linkId = linkId(card.getId(), mainAccount);
    participants.awaitMessage("VERIFY_ACCOUNT", linkId);

    participants.accountVerificationRejected(
        linkId, mainAccount, "ACCOUNT_NOT_OWNED", "The customer is not a holder of the account.");

    CardResponse rejected = awaitStatus(card.getId(), CardStatus.REJECTED);
    assertThat(rejected.getRejection().getCode()).isEqualTo("ACCOUNT_NOT_OWNED");
    assertThat(rejected.getRejection().getMessage())
        .isEqualTo("The customer is not a holder of the account.");
    LinkedAccount main = account(rejected, mainAccount);
    assertThat(main.getStatus()).isEqualTo(LinkStatus.REJECTED);
    assertThat(main.getRejection().getCode()).isEqualTo("ACCOUNT_NOT_OWNED");
    assertThat(main.getCurrency()).isNull();
    link(card.getId(), secondAccount)
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("CARD_NOT_ACTIVE");
  }

  @Test
  void aSecondAccountIsLinkedOnceAccountServiceConfirmsIt() {
    CardResponse card = activeCard(customer, mainAccount);
    link(card.getId(), secondAccount).expectStatus().isAccepted();
    UUID linkId = linkId(card.getId(), secondAccount);

    ReceivedMessage question = participants.awaitMessage("VERIFY_ACCOUNT", linkId);
    assertThat(question.key()).isEqualTo(secondAccount.toString());
    assertThat(question.field("customerId")).isEqualTo(customer.toString());
    // Linking an account is not acquiring a product: the overdue debt is not asked about.
    assertThat(question.body())
        .containsOnlyKeys("type", "transactionId", "accountId", "customerId", "replyTo");
    participants.accountVerified(linkId, secondAccount, customer, "USD");

    CardResponse linked = awaitLinkStatus(card.getId(), secondAccount, LinkStatus.LINKED);
    assertThat(linked.getStatus()).isEqualTo(CardStatus.ACTIVE);
    assertThat(linked.getMainAccountId()).isEqualTo(mainAccount);
    assertThat(account(linked, secondAccount).getCurrency()).isEqualTo("USD");
    assertThat(linked.getAccounts())
        .extracting(LinkedAccount::getAccountId)
        .containsExactly(mainAccount, secondAccount);
  }

  @Test
  void aSecondAccountThatIsRefusedLeavesTheCardActiveAndCanBeTriedAgain() {
    CardResponse card = activeCard(customer, mainAccount);
    link(card.getId(), secondAccount).expectStatus().isAccepted();
    UUID refusedLink = linkId(card.getId(), secondAccount);

    participants.accountVerificationRejected(
        refusedLink, secondAccount, "ACCOUNT_NOT_FOUND", "The account does not exist.");

    CardResponse refused = awaitLinkStatus(card.getId(), secondAccount, LinkStatus.REJECTED);
    assertThat(refused.getStatus()).isEqualTo(CardStatus.ACTIVE);
    assertThat(refused.getRejection()).isNull();
    assertThat(account(refused, secondAccount).getRejection().getCode())
        .isEqualTo("ACCOUNT_NOT_FOUND");

    link(card.getId(), secondAccount).expectStatus().isAccepted();
    UUID newLink = linkId(card.getId(), secondAccount);
    assertThat(newLink).isNotEqualTo(refusedLink);
    participants.awaitMessage("VERIFY_ACCOUNT", newLink);
    participants.accountVerified(newLink, secondAccount, customer, "PEN");

    CardResponse linked = awaitLinkStatus(card.getId(), secondAccount, LinkStatus.LINKED);
    assertThat(linked.getAccounts()).hasSize(2);
    assertThat(account(linked, secondAccount).getRejection()).isNull();
  }

  @Test
  void anAnswerThatArrivesAgainOrLateChangesNothing() {
    CardResponse card = issued(customer, mainAccount);
    UUID linkId = linkId(card.getId(), mainAccount);

    participants.accountVerified(linkId, mainAccount, customer, "PEN");
    participants.accountVerified(linkId, mainAccount, customer, "USD");
    participants.accountVerificationRejected(linkId, mainAccount, "ACCOUNT_NOT_FOUND", "Gone.");
    // An answer nobody waits for, then a card of its own to know the others were handled.
    UUID nobody = UUID.randomUUID();
    participants.accountVerified(nobody, mainAccount, customer, "PEN");
    participants.send(
        TestParticipants.CARD_REPLIES,
        linkId.toString(),
        "{\"type\":\"ACCOUNT_VERIFIED\",\"transactionId\":\"" + linkId + "\",\"currency\":\"EUR\"}",
        null);
    activeCard(customer, secondAccount);

    CardResponse active = awaitStatus(card.getId(), CardStatus.ACTIVE);
    assertThat(account(active, mainAccount).getCurrency()).isEqualTo("PEN");
    assertThat(active.getRejection()).isNull();
    assertThat(participants.countMessages(nobody, Duration.ZERO)).isZero();
  }

  @Test
  void repliesThatCannotBeReadAreSkippedAndTheNextOneIsHandled() {
    CardResponse card = issued(customer, mainAccount);
    UUID linkId = linkId(card.getId(), mainAccount);
    String key = linkId.toString();
    String id = "\"transactionId\":\"" + linkId + "\"";

    for (String unreadable :
        new String[] {
          "not json",
          "{" + id + "}",
          "{\"type\":\"ACCOUNT_DEBITED\"," + id + "}",
          "{\"type\":\"ACCOUNT_VERIFIED\"," + id + "}",
          "{\"type\":\"ACCOUNT_VERIFIED\",\"currency\":\"PEN\"}",
          "{\"type\":\"ACCOUNT_VERIFICATION_REJECTED\"," + id + ",\"code\":\"X\"}",
          "{\"type\":\"ACCOUNT_VERIFIED\",\"transactionId\":\"not-a-uuid\",\"currency\":\"PEN\"}",
          "[1,2,3]"
        }) {
      participants.send(TestParticipants.CARD_REPLIES, key, unreadable, "broken");
    }
    participants.accountVerified(linkId, mainAccount, customer, "PEN");

    assertThat(awaitStatus(card.getId(), CardStatus.ACTIVE).getMainAccountId())
        .isEqualTo(mainAccount);
  }

  @ParameterizedTest
  @CsvSource({
    "OVERDUE_DEBT, The customer has overdue debt in a credit product.",
    "CUSTOMER_INACTIVE, The customer is not active."
  })
  void aCardIsRejectedWithTheReasonAccountServiceGives(String code, String message) {
    CardResponse card = issued(customer, mainAccount);
    UUID linkId = linkId(card.getId(), mainAccount);
    participants.awaitMessage("VERIFY_ACCOUNT", linkId);

    participants.accountVerificationRejected(linkId, mainAccount, code, message);

    CardResponse rejected = awaitStatus(card.getId(), CardStatus.REJECTED);
    assertThat(rejected.getRejection().getCode()).isEqualTo(code);
    assertThat(rejected.getRejection().getMessage()).isEqualTo(message);
    assertThat(account(rejected, mainAccount).getStatus()).isEqualTo(LinkStatus.REJECTED);
    assertThat(account(rejected, mainAccount).getRejection().getCode()).isEqualTo(code);
  }

  @Test
  void theDocumentOfTheHolderIsStoredWithTheCardAndNeverShownByTheApi() {
    CardResponse card = issued(customer, mainAccount);
    UUID linkId = linkId(card.getId(), mainAccount);

    participants.accountVerified(linkId, mainAccount, customer, "PEN", "DNI", "45871236");
    awaitStatus(card.getId(), CardStatus.ACTIVE);
    // The answer about an account linked later does not change who the holder is.
    link(card.getId(), secondAccount).expectStatus().isAccepted();
    participants.accountVerified(
        linkId(card.getId(), secondAccount), secondAccount, customer, "USD", "CE", "000111");
    awaitLinkStatus(card.getId(), secondAccount, LinkStatus.LINKED);

    assertThat(holderColumn(card.getId(), "holder_document_type")).isEqualTo("DNI");
    assertThat(holderColumn(card.getId(), "holder_document_number")).isEqualTo("45871236");
    for (String uri : new String[] {"/api/v1/cards/" + card.getId(), "/api/v1/cards"}) {
      webTestClient
          .get()
          .uri(uri)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .value(
              body ->
                  assertThat(body)
                      .doesNotContain("45871236")
                      .doesNotContain("DNI")
                      .doesNotContainIgnoringCase("document"));
    }
  }

  @Test
  void aConfirmationWithoutADocumentLeavesTheCardWithoutOne() {
    CardResponse card = activeCard(customer, mainAccount);

    assertThat(holderColumn(card.getId(), "holder_document_type")).isNull();
    assertThat(holderColumn(card.getId(), "holder_document_number")).isNull();
  }

  private String holderColumn(UUID cardId, String column) {
    return databaseClient
        .sql("SELECT " + column + " AS doc FROM cards WHERE id = :id")
        .bind("id", cardId)
        .map(row -> Optional.ofNullable(row.get("doc", String.class)))
        .one()
        .block()
        .orElse(null);
  }
}
