package com.banking.cardservice.infrastructure.adapter.in.kafka;

import com.banking.cardservice.application.model.AccountVerification;
import com.banking.cardservice.domain.model.vo.HolderDocument;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * An answer of Account Service to VERIFY_ACCOUNT as it travels in Kafka: one JSON object whose
 * {@code type} says what happened. Fields this service does not know are ignored, so Account
 * Service can add data without breaking it.
 *
 * @param transactionId identifier of the link the question was about
 * @param currency ACCOUNT_VERIFIED only: currency of the account
 * @param documentType ACCOUNT_VERIFIED only: type of the identity document of the customer
 * @param documentNumber ACCOUNT_VERIFIED only: number of that document
 * @param code ACCOUNT_VERIFICATION_REJECTED only: error code of Account Service
 * @param message ACCOUNT_VERIFICATION_REJECTED only: its explanation
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountReplyMessage(
    String type,
    UUID transactionId,
    String currency,
    String documentType,
    String documentNumber,
    String code,
    String message) {

  /**
   * The answer in the terms of the application.
   *
   * @throws IllegalArgumentException when the type is missing or unknown
   * @throws NullPointerException when a field the type needs is missing
   */
  AccountVerification toVerification() {
    if (type == null) {
      throw new IllegalArgumentException("The reply has no type.");
    }
    return switch (type) {
      case "ACCOUNT_VERIFIED" ->
          new AccountVerification.Verified(
              transactionId,
              currency,
              HolderDocument.of(documentType, documentNumber).orElse(null));
      case "ACCOUNT_VERIFICATION_REJECTED" ->
          new AccountVerification.Refused(transactionId, code, message);
      default -> throw new IllegalArgumentException("Unknown reply type.");
    };
  }
}
