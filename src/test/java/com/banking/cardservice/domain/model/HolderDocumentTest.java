package com.banking.cardservice.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HolderDocumentTest {

  @Test
  void aTypeAndANumberMakeADocument() {
    assertThat(HolderDocument.of("DNI", "45871236"))
        .contains(new HolderDocument("DNI", "45871236"));
    assertThat(
            HolderDocument.of(
                "T".repeat(HolderDocument.MAX_TYPE_LENGTH),
                "9".repeat(HolderDocument.MAX_NUMBER_LENGTH)))
        .isPresent();
  }

  @Test
  void aDocumentWithAMissingBlankOrTooLongValueIsNotKnown() {
    assertThat(HolderDocument.of(null, "45871236")).isEmpty();
    assertThat(HolderDocument.of("DNI", null)).isEmpty();
    assertThat(HolderDocument.of(" ", "45871236")).isEmpty();
    assertThat(HolderDocument.of("DNI", "")).isEmpty();
    assertThat(HolderDocument.of("T".repeat(HolderDocument.MAX_TYPE_LENGTH + 1), "1")).isEmpty();
    assertThat(HolderDocument.of("DNI", "9".repeat(HolderDocument.MAX_NUMBER_LENGTH + 1)))
        .isEmpty();
  }

  @Test
  void bothValuesAreRequired() {
    assertThatThrownBy(() -> new HolderDocument(null, "45871236"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new HolderDocument("DNI", null))
        .isInstanceOf(NullPointerException.class);
  }
}
