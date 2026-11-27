package com.banking.cardservice.application.port.in;

import com.banking.cardservice.application.command.LinkAccountCommand;
import com.banking.cardservice.domain.model.Card;
import reactor.core.publisher.Mono;

/** Links another account to a card and starts its check. */
public interface LinkAccountUseCase {

  /** Returns the card with the new account, waiting for the check. */
  Mono<Card> execute(LinkAccountCommand command);
}
