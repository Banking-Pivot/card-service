package com.banking.cardservice.application.model;

import java.util.UUID;

/**
 * The question Account Service is asked about an account: does it exist and does it belong to this
 * customer. When the account is the first one of a new card the question also asks that the
 * customer has no overdue debt, because a card is a product the customer acquires.
 *
 * @param linkId identifier of the link, which the answer carries back
 * @param accountId the account
 * @param customerId customer of the card
 * @param correlationId correlation identifier of the request that asked for the link
 * @param requireNoOverdueDebt whether a customer with overdue debt must be refused
 */
public record VerificationRequest(
    UUID linkId,
    UUID accountId,
    UUID customerId,
    String correlationId,
    boolean requireNoOverdueDebt) {}
