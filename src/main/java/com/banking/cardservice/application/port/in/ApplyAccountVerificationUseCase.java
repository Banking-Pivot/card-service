package com.banking.cardservice.application.port.in;

import com.banking.cardservice.application.model.AccountVerification;
import reactor.core.publisher.Mono;

/** Applies the answer of Account Service about an account to the card that asked. */
public interface ApplyAccountVerificationUseCase {

  /** Completes once the answer is stored, or once it is known that nothing waits for it. */
  Mono<Void> execute(AccountVerification verification);
}
