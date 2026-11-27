package com.banking.cardservice.application.port.in;

import com.banking.cardservice.domain.model.Card;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Reads one card. */
public interface GetCardUseCase {

  /** Returns the card, or fails with CardNotFoundException. */
  Mono<Card> execute(UUID cardId);
}
