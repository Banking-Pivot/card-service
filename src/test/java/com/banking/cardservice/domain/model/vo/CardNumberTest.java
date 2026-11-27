package com.banking.cardservice.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CardNumberTest {

  @Test
  void aGeneratedNumberHasSixteenDigitsTheBankPrefixAndAValidCheckDigit() {
    Random random = new Random(7);

    for (int attempt = 0; attempt < 500; attempt++) {
      CardNumber number = CardNumber.generate(() -> random.nextInt(10));

      assertThat(number.value()).matches("455678\\d{10}");
      assertThat(passesLuhn(number.value())).as(number.value()).isTrue();
    }
  }

  @Test
  void aSourceOfDigitsOutOfRangeStillGivesDigits() {
    assertThat(CardNumber.generate(() -> 37).value()).startsWith("455678777777777");
    assertThat(CardNumber.generate(() -> -1).value()).startsWith("455678999999999");
  }

  @ParameterizedTest
  @ValueSource(strings = {"4111111111111111", "5500005555555559", "4000000000000002"})
  void aNumberWithAValidCheckDigitIsAccepted(String valid) {
    assertThat(new CardNumber(valid).value()).isEqualTo(valid);
    assertThat(passesLuhn(valid)).isTrue();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "411111111111111",
        "41111111111111111",
        "4111-1111-1111-1111",
        "4111 1111 1111 1111",
        "411111111111111a",
        "4111111111111112",
        "4111111111111121"
      })
  void anythingElseIsNotACardNumber(String invalid) {
    assertThatThrownBy(() -> new CardNumber(invalid)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void onlyTheLastFourDigitsAreEverShown() {
    CardNumber number = new CardNumber("4111111111111111");

    assertThat(number.masked()).isEqualTo("**** **** **** 1111");
    assertThat(number).hasToString("**** **** **** 1111");
    assertThat("card " + number).doesNotContain("411111");
  }

  // Written apart from the code under test: doubles every second digit from the right.
  private static boolean passesLuhn(String number) {
    int sum = 0;
    for (int index = 0; index < number.length(); index++) {
      int digit = number.charAt(number.length() - 1 - index) - '0';
      if (index % 2 == 1) {
        digit = digit * 2 > 9 ? digit * 2 - 9 : digit * 2;
      }
      sum += digit;
    }
    return sum % 10 == 0;
  }
}
