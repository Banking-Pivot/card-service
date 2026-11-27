package com.banking.cardservice.domain.model;

import java.util.function.IntSupplier;

/**
 * Number of a debit card: 16 digits, the last one the Luhn check digit of the other fifteen.
 *
 * @param value the 16 digits, without spaces
 */
public record CardNumber(String value) {

  /** Digits every card of the bank starts with. */
  public static final String ISSUER_PREFIX = "455678";

  private static final int LENGTH = 16;
  private static final int VISIBLE = 4;

  /** Rejects anything that is not 16 digits ending in the right check digit. */
  public CardNumber {
    if (value == null || !value.matches("\\d{" + LENGTH + "}")) {
      throw new IllegalArgumentException("A card number has " + LENGTH + " digits.");
    }
    if (checkDigit(value.substring(0, LENGTH - 1)) != value.charAt(LENGTH - 1) - '0') {
      throw new IllegalArgumentException("The check digit of the card number is wrong.");
    }
  }

  /**
   * A new number of the bank: the issuer prefix, nine digits taken from {@code digits} and the
   * check digit.
   *
   * @param digits source of digits; each call returns a value from 0 to 9
   */
  public static CardNumber generate(IntSupplier digits) {
    StringBuilder number = new StringBuilder(ISSUER_PREFIX);
    while (number.length() < LENGTH - 1) {
      number.append(Math.floorMod(digits.getAsInt(), 10));
    }
    return new CardNumber(number.append(checkDigit(number.toString())).toString());
  }

  /** The number as it can be shown: only the last four digits. */
  public String masked() {
    return "**** **** **** " + value.substring(LENGTH - VISIBLE);
  }

  /** Never the whole number, so it cannot end up in a log by accident. */
  @Override
  public String toString() {
    return masked();
  }

  // Luhn: from the right, every second digit is doubled and its digits are added.
  private static int checkDigit(String payload) {
    int sum = 0;
    boolean doubled = true;
    for (int index = payload.length() - 1; index >= 0; index--) {
      int digit = payload.charAt(index) - '0';
      if (doubled) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      sum += digit;
      doubled = !doubled;
    }
    return (10 - sum % 10) % 10;
  }
}
