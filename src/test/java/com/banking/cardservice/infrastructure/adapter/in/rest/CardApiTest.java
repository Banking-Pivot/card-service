package com.banking.cardservice.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardPageResponse;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardResponse;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardStatus;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.LinkStatus;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.LinkedAccount;
import com.banking.cardservice.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The REST API of cards: requests, rules, validation and queries. */
class CardApiTest extends IntegrationTest {

  private final UUID customer = UUID.randomUUID();
  private final UUID mainAccount = UUID.randomUUID();
  private final UUID secondAccount = UUID.randomUUID();

  @Test
  void aCardIsAcceptedWaitingForItsMainAccountAndShowsItsNumberMasked() {
    CardResponse card =
        issue(customer, mainAccount)
            .expectStatus()
            .isAccepted()
            .expectHeader()
            .valueMatches("Location", "/api/v1/cards/[0-9a-f-]{36}")
            .expectBody(CardResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(card.getStatus()).isEqualTo(CardStatus.PENDING_VERIFICATION);
    assertThat(card.getCustomerId()).isEqualTo(customer);
    assertThat(card.getMainAccountId()).isEqualTo(mainAccount);
    assertThat(card.getRejection()).isNull();
    assertThat(card.getAccounts()).hasSize(1);
    LinkedAccount main = card.getAccounts().get(0);
    assertThat(main.getAccountId()).isEqualTo(mainAccount);
    assertThat(main.getPosition()).isEqualTo(1);
    assertThat(main.getMain()).isTrue();
    assertThat(main.getStatus()).isEqualTo(LinkStatus.PENDING);
    assertThat(main.getCurrency()).isNull();

    String number = storedNumber(card.getId());
    assertThat(number).matches("455678\\d{10}");
    assertThat(card.getMaskedNumber()).isEqualTo("**** **** **** " + number.substring(12));
    webTestClient
        .get()
        .uri("/api/v1/cards/{id}", card.getId())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(
            body ->
                assertThat(body)
                    .doesNotContain(number)
                    .doesNotContain("rejection")
                    .doesNotContain("currency"));
  }

  @Test
  void twoCardsNeverShareANumber() {
    CardResponse first = issued(customer, mainAccount);
    CardResponse second = issued(customer, mainAccount);

    assertThat(storedNumber(first.getId())).isNotEqualTo(storedNumber(second.getId()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"customerId\":\"7c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f\"}",
        "{\"mainAccountId\":\"7c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f\"}",
        "{\"customerId\":\"not-a-uuid\",\"mainAccountId\":\"7c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f\"}",
        "{\"customerId\":\"7c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f\","
            + "\"mainAccountId\":\"7c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f\",\"status\":\"ACTIVE\"}",
        "not json"
      })
  void aRequestThatDoesNotFollowTheContractIsRefused(String body) {
    post("/api/v1/cards", body)
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_REQUEST")
        .jsonPath("$.path")
        .isEqualTo("/api/v1/cards")
        .jsonPath("$.traceId")
        .isNotEmpty()
        .jsonPath("$.timestamp")
        .isNotEmpty();
  }

  @Test
  void aRequestThatIsNotJsonIsRefused() {
    webTestClient
        .post()
        .uri("/api/v1/cards")
        .contentType(MediaType.TEXT_PLAIN)
        .bodyValue("customerId=1")
        .exchange()
        .expectStatus()
        .isEqualTo(415)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    webTestClient
        .get()
        .uri("/api/v1/cards/not-a-uuid")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("INVALID_REQUEST");
  }

  @Test
  void aCardThatDoesNotExistIsNotFoundWhateverIsAsked() {
    UUID unknown = UUID.randomUUID();

    expectError(
        webTestClient.get().uri("/api/v1/cards/{id}", unknown).exchange(), 404, "CARD_NOT_FOUND");
    expectError(link(unknown, secondAccount), 404, "CARD_NOT_FOUND");
    expectError(changeMainAccount(unknown, secondAccount), 404, "CARD_NOT_FOUND");
    expectError(unlink(unknown, secondAccount), 404, "CARD_NOT_FOUND");
    expectError(block(unknown), 404, "CARD_NOT_FOUND");
    expectError(unblock(unknown), 404, "CARD_NOT_FOUND");
  }

  @Test
  void cardsAreListedNewestFirstByPageAndByCustomer() {
    CardResponse oldest = issued(customer, mainAccount);
    CardResponse newest = issued(customer, secondAccount);
    issued(UUID.randomUUID(), UUID.randomUUID());

    CardPageResponse all = page("/api/v1/cards");
    CardPageResponse firstPage = page("/api/v1/cards?customerId=" + customer + "&size=1");
    CardPageResponse secondPage = page("/api/v1/cards?customerId=" + customer + "&size=1&page=1");
    CardPageResponse beyond = page("/api/v1/cards?customerId=" + customer + "&page=7");

    assertThat(all.getTotalElements()).isEqualTo(3);
    assertThat(all.getSize()).isEqualTo(20);
    assertThat(firstPage.getItems())
        .extracting(CardResponse::getId)
        .containsExactly(newest.getId());
    assertThat(firstPage.getTotalElements()).isEqualTo(2);
    assertThat(firstPage.getTotalPages()).isEqualTo(2);
    assertThat(firstPage.getItems().get(0).getAccounts()).hasSize(1);
    assertThat(secondPage.getItems())
        .extracting(CardResponse::getId)
        .containsExactly(oldest.getId());
    assertThat(secondPage.getPage()).isEqualTo(1);
    assertThat(beyond.getItems()).isEmpty();
    assertThat(page("/api/v1/cards?customerId=" + UUID.randomUUID()).getTotalElements()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=x", "customerId=abc"})
  void aListWithParametersOutOfRangeIsRefused(String query) {
    expectError(
        webTestClient.get().uri("/api/v1/cards?" + query).exchange(), 400, "INVALID_REQUEST");
  }

  @Test
  void anAccountIsAcceptedWaitingAndCannotBeLinkedTwice() {
    CardResponse card = activeCard(customer, mainAccount);

    CardResponse asked =
        link(card.getId(), secondAccount)
            .expectStatus()
            .isAccepted()
            .expectBody(CardResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(asked.getStatus()).isEqualTo(CardStatus.ACTIVE);
    assertThat(asked.getMainAccountId()).isEqualTo(mainAccount);
    LinkedAccount second = account(asked, secondAccount);
    assertThat(second.getStatus()).isEqualTo(LinkStatus.PENDING);
    assertThat(second.getPosition()).isEqualTo(2);
    assertThat(second.getMain()).isFalse();
    expectError(link(card.getId(), secondAccount), 409, "ACCOUNT_ALREADY_LINKED");
    expectError(link(card.getId(), mainAccount), 409, "ACCOUNT_ALREADY_LINKED");
    expectError(
        post("/api/v1/cards/{cardId}/accounts", "{}", card.getId()), 400, "INVALID_REQUEST");
  }

  @Test
  void aCardThatIsNotActiveTakesNoChanges() {
    CardResponse pending = issued(customer, mainAccount);

    expectError(link(pending.getId(), secondAccount), 422, "CARD_NOT_ACTIVE");
    expectError(changeMainAccount(pending.getId(), mainAccount), 422, "CARD_NOT_ACTIVE");
    expectError(unlink(pending.getId(), mainAccount), 422, "CARD_NOT_ACTIVE");
    expectError(block(pending.getId()), 422, "CARD_NOT_ACTIVE");
    expectError(unblock(pending.getId()), 422, "CARD_NOT_ACTIVE");
    assertThat(card(pending.getId()).getStatus()).isEqualTo(CardStatus.PENDING_VERIFICATION);
  }

  @Test
  void theMainAccountChangesToAnotherLinkedAccount() {
    CardResponse card = activeCard(customer, mainAccount);
    linked(card.getId(), customer, secondAccount);

    CardResponse changed =
        changeMainAccount(card.getId(), secondAccount)
            .expectStatus()
            .isOk()
            .expectBody(CardResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(changed.getMainAccountId()).isEqualTo(secondAccount);
    assertThat(account(changed, secondAccount).getMain()).isTrue();
    assertThat(account(changed, mainAccount).getMain()).isFalse();
    assertThat(account(changed, mainAccount).getPosition()).isEqualTo(1);
    assertThat(card(card.getId()).getMainAccountId()).isEqualTo(secondAccount);
    // Choosing it again is the same answer.
    changeMainAccount(card.getId(), secondAccount).expectStatus().isOk();
  }

  @Test
  void onlyALinkedAccountCanBecomeTheMainOne() {
    CardResponse card = activeCard(customer, mainAccount);
    link(card.getId(), secondAccount).expectStatus().isAccepted();

    expectError(changeMainAccount(card.getId(), secondAccount), 422, "ACCOUNT_NOT_LINKED");
    expectError(changeMainAccount(card.getId(), UUID.randomUUID()), 422, "ACCOUNT_NOT_LINKED");
    assertThat(card(card.getId()).getMainAccountId()).isEqualTo(mainAccount);
  }

  @Test
  void anAccountIsUnlinkedUnlessItIsTheMainOne() {
    CardResponse card = activeCard(customer, mainAccount);
    linked(card.getId(), customer, secondAccount);

    expectError(unlink(card.getId(), mainAccount), 422, "MAIN_ACCOUNT_REQUIRED");
    expectError(unlink(card.getId(), UUID.randomUUID()), 404, "ACCOUNT_NOT_LINKED");
    unlink(card.getId(), secondAccount).expectStatus().isNoContent().expectBody().isEmpty();

    assertThat(card(card.getId()).getAccounts())
        .extracting(LinkedAccount::getAccountId)
        .containsExactly(mainAccount);
    expectError(unlink(card.getId(), secondAccount), 404, "ACCOUNT_NOT_LINKED");
    // Its place is free again.
    assertThat(account(linked(card.getId(), customer, secondAccount), secondAccount).getPosition())
        .isEqualTo(2);
  }

  @Test
  void aCardIsBlockedAndUnblocked() {
    CardResponse card = activeCard(customer, mainAccount);

    block(card.getId())
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("BLOCKED");
    block(card.getId())
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("BLOCKED");

    assertThat(card(card.getId()).getStatus()).isEqualTo(CardStatus.BLOCKED);
    expectError(link(card.getId(), secondAccount), 422, "CARD_NOT_ACTIVE");

    unblock(card.getId())
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("ACTIVE");
    unblock(card.getId())
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("ACTIVE");
    link(card.getId(), secondAccount).expectStatus().isAccepted();
  }

  private WebTestClient.ResponseSpec unlink(UUID cardId, UUID accountId) {
    return webTestClient
        .delete()
        .uri("/api/v1/cards/{cardId}/accounts/{accountId}", cardId, accountId)
        .exchange();
  }

  private WebTestClient.ResponseSpec unblock(UUID cardId) {
    return webTestClient.post().uri("/api/v1/cards/{cardId}/unblock", cardId).exchange();
  }

  private CardPageResponse page(String uri) {
    return webTestClient
        .get()
        .uri(uri)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(CardPageResponse.class)
        .returnResult()
        .getResponseBody();
  }

  private static void expectError(WebTestClient.ResponseSpec response, int status, String code) {
    response
        .expectStatus()
        .isEqualTo(status)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo(code)
        .jsonPath("$.message")
        .isNotEmpty()
        .jsonPath("$.traceId")
        .isNotEmpty();
  }

  private String storedNumber(UUID cardId) {
    return databaseClient
        .sql("SELECT card_number FROM cards WHERE id = :id")
        .bind("id", cardId)
        .map(found -> found.get(0, String.class))
        .one()
        .block();
  }

  @Test
  void aCustomerGetsAtMostThreeCardsNotCountingTheRejectedOnes() {
    CardResponse refused = issued(customer, mainAccount);
    participants.accountVerificationRejected(
        linkId(refused.getId(), mainAccount),
        mainAccount,
        "OVERDUE_DEBT",
        "The customer has overdue debt in a credit product.");
    awaitStatus(refused.getId(), CardStatus.REJECTED);
    // One card waiting, one active and one blocked: the three the customer may have.
    issued(customer, mainAccount);
    activeCard(customer, secondAccount);
    block(activeCard(customer, UUID.randomUUID()).getId()).expectStatus().isOk();

    issue(customer, mainAccount)
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("CARD_LIMIT_REACHED")
        .jsonPath("$.message")
        .isEqualTo("The customer already has the maximum number of cards.")
        .jsonPath("$.path")
        .isEqualTo("/api/v1/cards");

    // The refused request left nothing behind, and another customer is not affected.
    webTestClient
        .get()
        .uri("/api/v1/cards?customerId={id}", customer)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.totalElements")
        .isEqualTo(4);
    issue(UUID.randomUUID(), mainAccount).expectStatus().isAccepted();
  }
}
