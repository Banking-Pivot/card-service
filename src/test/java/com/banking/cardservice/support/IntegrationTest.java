package com.banking.cardservice.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardResponse;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardStatus;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.LinkStatus;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.LinkedAccount;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the whole service on a random port against a real PostgreSQL and a real Kafka in Docker.
 *
 * <p>The application keeps its real {@code spring.config.import}. A stub Config Server serves the
 * settings of {@code stub-config/card-service.yml} and the locations of the containers; a real
 * Vault holds the database user and password, which the Config Server never serves. All of them
 * start once, before any Spring context. {@link TestParticipants} plays Account Service,
 * Transaction Service and Yanki Service on the same Kafka.
 *
 * <p>Every test starts with empty tables. The topics are not emptied: every test uses its own
 * identifiers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public abstract class IntegrationTest {

  protected static final String DATABASE = "cards_db";
  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:16-alpine").withDatabaseName(DATABASE);
  protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");
  protected static final StubConfigServer CONFIG_SERVER;
  protected static final TestVault VAULT;

  private static TestParticipants connected;

  static {
    POSTGRES.start();
    KAFKA.start();
    // Read by application.yaml through VAULT_URI and VAULT_APPROLE_FILE.
    VAULT =
        TestVault.start(
            "card-service",
            Map.of(
                "spring.r2dbc.username", POSTGRES.getUsername(),
                "spring.r2dbc.password", POSTGRES.getPassword(),
                "spring.flyway.user", POSTGRES.getUsername(),
                "spring.flyway.password", POSTGRES.getPassword()));
    Map<String, String> properties = new HashMap<>(yaml("stub-config/card-service.yml"));
    properties.put(
        "spring.r2dbc.url",
        "r2dbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(5432)
            + "/"
            + DATABASE);
    properties.put("spring.flyway.url", POSTGRES.getJdbcUrl());
    properties.put("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
    // No registry in the tests: the service must not register with a real Eureka.
    properties.put("eureka.client.enabled", "false");
    CONFIG_SERVER = StubConfigServer.start(properties);
    // Read by application.yaml: configserver:${CONFIG_SERVER_URL:http://localhost:8888}
    System.setProperty("CONFIG_SERVER_URL", CONFIG_SERVER.url());
  }

  @Autowired protected WebTestClient webTestClient;

  @Autowired protected DatabaseClient databaseClient;

  protected TestParticipants participants;

  @BeforeEach
  void startClean() {
    databaseClient.sql("TRUNCATE card_accounts, cards").then().block();
    participants = participants();
    webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(15)).build();
  }

  // One connection for all the tests, opened when the first context is up and the topics exist.
  private static synchronized TestParticipants participants() {
    if (connected == null) {
      connected = TestParticipants.connect(KAFKA.getBootstrapServers());
    }
    return connected;
  }

  private static Map<String, String> yaml(String resource) {
    YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
    factory.setResources(new ClassPathResource(resource));
    Properties loaded = factory.getObject();
    Map<String, String> properties = new HashMap<>();
    loaded.forEach((key, value) -> properties.put(key.toString(), value.toString()));
    return properties;
  }

  // ----- requests

  protected static Map<String, Object> body(String field, UUID value) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(field, value.toString());
    return body;
  }

  protected WebTestClient.ResponseSpec post(String uri, Object body, Object... variables) {
    return webTestClient
        .post()
        .uri(uri, variables)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  protected WebTestClient.ResponseSpec issue(UUID customerId, UUID mainAccountId) {
    Map<String, Object> request = body("customerId", customerId);
    request.put("mainAccountId", mainAccountId.toString());
    return post("/api/v1/cards", request);
  }

  /** Asks for a card and returns it as it was accepted: waiting for its main account. */
  protected CardResponse issued(UUID customerId, UUID mainAccountId) {
    return issue(customerId, mainAccountId)
        .expectStatus()
        .isAccepted()
        .expectBody(CardResponse.class)
        .returnResult()
        .getResponseBody();
  }

  /** A card whose main account, in soles, Account Service already confirmed. */
  protected CardResponse activeCard(UUID customerId, UUID mainAccountId) {
    CardResponse card = issued(customerId, mainAccountId);
    participants.accountVerified(
        linkId(card.getId(), mainAccountId), mainAccountId, customerId, "PEN");
    return awaitStatus(card.getId(), CardStatus.ACTIVE);
  }

  protected WebTestClient.ResponseSpec link(UUID cardId, UUID accountId) {
    return post("/api/v1/cards/{cardId}/accounts", body("accountId", accountId), cardId);
  }

  /** Links one more account, in dollars, and waits until Account Service confirmed it. */
  protected CardResponse linked(UUID cardId, UUID customerId, UUID accountId) {
    link(cardId, accountId).expectStatus().isAccepted();
    participants.accountVerified(linkId(cardId, accountId), accountId, customerId, "USD");
    return awaitLinkStatus(cardId, accountId, LinkStatus.LINKED);
  }

  protected WebTestClient.ResponseSpec changeMainAccount(UUID cardId, UUID accountId) {
    return webTestClient
        .put()
        .uri("/api/v1/cards/{cardId}/main-account", cardId)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("accountId", accountId))
        .exchange();
  }

  protected WebTestClient.ResponseSpec block(UUID cardId) {
    return webTestClient.post().uri("/api/v1/cards/{cardId}/block", cardId).exchange();
  }

  protected CardResponse card(UUID cardId) {
    return webTestClient
        .get()
        .uri("/api/v1/cards/{id}", cardId)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(CardResponse.class)
        .returnResult()
        .getResponseBody();
  }

  protected static LinkedAccount account(CardResponse card, UUID accountId) {
    return card.getAccounts().stream()
        .filter(linked -> linked.getAccountId().equals(accountId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("The card has no account " + accountId));
  }

  /** Waits until the card reaches the status and returns it. */
  protected CardResponse awaitStatus(UUID cardId, CardStatus status) {
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(card(cardId).getStatus()).isEqualTo(status));
    return card(cardId);
  }

  /** Waits until the link of the account reaches the status and returns the card. */
  protected CardResponse awaitLinkStatus(UUID cardId, UUID accountId, LinkStatus status) {
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> assertThat(account(card(cardId), accountId).getStatus()).isEqualTo(status));
    return card(cardId);
  }

  /** Identifier of the link between the card and the account: the transactionId of its check. */
  protected UUID linkId(UUID cardId, UUID accountId) {
    return databaseClient
        .sql("SELECT id FROM card_accounts WHERE card_id = :card AND account_id = :account")
        .bind("card", cardId)
        .bind("account", accountId)
        .map(row -> row.get(0, UUID.class))
        .one()
        .block();
  }

  protected long linkColumn(UUID linkId, String column) {
    return databaseClient
        .sql("SELECT " + column + " FROM card_accounts WHERE id = :id")
        .bind("id", linkId)
        .map(row -> row.get(0, Number.class).longValue())
        .one()
        .block();
  }
}
