package com.banking.cardservice.infrastructure.adapter.out.kafka;

import com.banking.cardservice.application.model.VerificationRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * The VERIFY_ACCOUNT command as it travels in Kafka. {@code requireNoOverdueDebt} is sent as true
 * when a card is being issued and left out otherwise.
 *
 * @param transactionId identifier of the link; the answer carries it back
 * @param accountId the account asked about
 * @param customerId customer who must be a holder of the account
 * @param replyTo topic Account Service answers on
 * @param requireNoOverdueDebt true when a customer with overdue debt must be refused, else null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerifyAccountMessage(
    String type,
    UUID transactionId,
    UUID accountId,
    UUID customerId,
    String replyTo,
    Boolean requireNoOverdueDebt) {

  static final String VERIFY_ACCOUNT = "VERIFY_ACCOUNT";

  static VerifyAccountMessage of(VerificationRequest request, String replyTo) {
    return new VerifyAccountMessage(
        VERIFY_ACCOUNT,
        request.linkId(),
        request.accountId(),
        request.customerId(),
        replyTo,
        request.requireNoOverdueDebt() ? Boolean.TRUE : null);
  }
}
