package com.banking.cardservice.application.port.in;

import com.banking.cardservice.application.model.CardResolution;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Answers which accounts a card uses, for the services that move the money. */
public interface ResolveCardUseCase {

  /** Always returns an answer: the accounts, or why the card cannot be used. */
  Mono<CardResolution> execute(UUID cardId);
}
