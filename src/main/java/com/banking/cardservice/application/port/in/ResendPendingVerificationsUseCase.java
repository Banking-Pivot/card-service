package com.banking.cardservice.application.port.in;

import reactor.core.publisher.Mono;

/** Asks Account Service again about every account that has waited too long for its answer. */
public interface ResendPendingVerificationsUseCase {

  /** Returns how many questions were sent again. */
  Mono<Long> execute();
}
