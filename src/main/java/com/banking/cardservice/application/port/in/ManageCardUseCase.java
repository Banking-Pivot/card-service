package com.banking.cardservice.application.port.in;

import com.banking.cardservice.domain.model.Card;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Changes to a card that need nobody else: its main account, unlinking, blocking, cancelling. */
public interface ManageCardUseCase {

  /** Makes a linked account the one the card charges first. */
  Mono<Card> changeMainAccount(UUID cardId, UUID accountId);

  /** Removes an account from the card. */
  Mono<Card> unlinkAccount(UUID cardId, UUID accountId);

  /** Blocks the card. */
  Mono<Card> block(UUID cardId);

  /** Unblocks the card. */
  Mono<Card> unblock(UUID cardId);

  /** Cancels the card for good. */
  Mono<Card> cancel(UUID cardId);
}
