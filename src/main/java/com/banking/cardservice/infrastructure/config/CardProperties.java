package com.banking.cardservice.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of Card Service, served by the Config Server under {@code card}.
 *
 * @param messaging topics and times of the conversations over the event broker
 * @param limits how many cards a customer may have
 */
@Validated
@ConfigurationProperties("card")
public record CardProperties(
    @NotNull @Valid @DefaultValue Messaging messaging,
    @NotNull @Valid @DefaultValue Limits limits) {

  /**
   * Limits of the bank on cards.
   *
   * @param maxCardsPerCustomer most cards a customer may have, not counting the rejected and the
   *     cancelled ones
   */
  public record Limits(@DefaultValue("3") @Min(1) int maxCardsPerCustomer) {}

  /**
   * How messages go out and come in.
   *
   * @param commandsTopic topic this service reads RESOLVE_CARD from
   * @param repliesTopic topic Account Service answers this service on
   * @param accountCommandsTopic topic Account Service reads its commands from
   * @param defaultReplyTopic where the answer to a command that names no {@code replyTo} goes
   * @param replyTopics the only topics a command may name in {@code replyTo}
   * @param publishTimeout longest a send may take before it counts as failed
   * @param retryInterval wait between the three retries of a message that could not be handled
   * @param resendInterval how often the accounts that still wait are looked at
   * @param resendAfter how long a question waits for its answer before it is asked again
   * @param resendBatchSize most pending accounts asked about again in one look
   * @param handlingTimeout longest a message may take to be handled before it is retried
   */
  public record Messaging(
      @DefaultValue("banking.card.commands") @NotBlank String commandsTopic,
      @DefaultValue("banking.card.replies") @NotBlank String repliesTopic,
      @DefaultValue("banking.account.commands") @NotBlank String accountCommandsTopic,
      @DefaultValue("banking.transaction.replies") @NotBlank String defaultReplyTopic,
      @DefaultValue({"banking.transaction.replies", "banking.yanki.replies"}) @NotEmpty
          List<@NotBlank String> replyTopics,
      @DefaultValue("3s") @NotNull Duration publishTimeout,
      @DefaultValue("1s") @NotNull Duration retryInterval,
      @DefaultValue("10s") @NotNull Duration resendInterval,
      @DefaultValue("30s") @NotNull Duration resendAfter,
      @DefaultValue("50") @Min(1) int resendBatchSize,
      @DefaultValue("30s") @NotNull Duration handlingTimeout) {

    /** Whether an answer may be sent to that topic. */
    public boolean allowsReplyTo(String topic) {
      return replyTopics.contains(topic);
    }
  }
}
