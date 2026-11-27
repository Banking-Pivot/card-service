package com.banking.cardservice.support;

import com.banking.cardservice.domain.model.Card;
import com.banking.cardservice.domain.model.vo.CardNumber;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** Cards for tests, all of one customer and born at {@link #START}. */
public final class Cards {

  public static final Instant START = Instant.parse("2026-11-20T15:00:00Z");
  public static final UUID CUSTOMER = id(900);
  public static final CardNumber NUMBER = CardNumber.generate(() -> 0);

  private Cards() {}

  /** A readable identifier: the same number always gives the same UUID. */
  public static UUID id(int number) {
    return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", number));
  }

  /** A card that still waits for the answer about its main account. */
  public static Card pending(UUID cardId, UUID linkId, UUID accountId) {
    return Card.issue(cardId, CUSTOMER, NUMBER, linkId, accountId, "corr-" + linkId, START);
  }

  /** A card whose main account, in soles, was confirmed one second after it was issued. */
  public static Card active(UUID cardId, UUID linkId, UUID accountId) {
    return pending(cardId, linkId, accountId)
        .accountVerified(linkId, "PEN", START.plusSeconds(1))
        .orElseThrow();
  }

  /** An active card with a second account linked and confirmed, in dollars. */
  public static Card withTwoAccounts(
      UUID cardId, UUID mainLinkId, UUID mainAccountId, UUID secondLinkId, UUID secondAccountId) {
    return active(cardId, mainLinkId, mainAccountId)
        .linkAccount(secondLinkId, secondAccountId, "corr-" + secondLinkId, START.plusSeconds(2))
        .accountVerified(secondLinkId, "USD", START.plusSeconds(3))
        .orElseThrow();
  }

  /** A clock stopped that many seconds after {@link #START}. */
  public static Clock clockAt(long seconds) {
    return Clock.fixed(START.plusSeconds(seconds), ZoneOffset.UTC);
  }
}
