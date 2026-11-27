package com.banking.cardservice.application.port.in;

import com.banking.cardservice.application.command.IssueCardCommand;
import com.banking.cardservice.domain.model.Card;
import reactor.core.publisher.Mono;

/** Issues a debit card and starts the check of its main account. */
public interface IssueCardUseCase {

  /** Returns the new card, waiting for the check. */
  Mono<Card> execute(IssueCardCommand command);
}
