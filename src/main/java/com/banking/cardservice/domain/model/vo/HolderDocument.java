package com.banking.cardservice.domain.model.vo;

import java.util.Objects;
import java.util.Optional;

/**
 * Identity document of the customer who holds a card, as Account Service reports it when it
 * confirms the first account of the card. It never leaves the bank: it is told to the services that
 * ask which accounts a card uses, not to the callers of the API.
 *
 * @param type document type code, such as DNI
 * @param number document number
 */
public record HolderDocument(String type, String number) {

  /** Longest document type the bank registers. */
  public static final int MAX_TYPE_LENGTH = 24;

  /** Longest document number the bank registers. */
  public static final int MAX_NUMBER_LENGTH = 32;

  /** Both values are required. */
  public HolderDocument {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(number, "number");
  }

  /**
   * The document made of those two values, or empty when it is not known: one of them is missing,
   * blank or longer than the bank registers.
   */
  public static Optional<HolderDocument> of(String type, String number) {
    if (unusable(type, MAX_TYPE_LENGTH) || unusable(number, MAX_NUMBER_LENGTH)) {
      return Optional.empty();
    }
    return Optional.of(new HolderDocument(type, number));
  }

  private static boolean unusable(String value, int maxLength) {
    return value == null || value.isBlank() || value.length() > maxLength;
  }
}
