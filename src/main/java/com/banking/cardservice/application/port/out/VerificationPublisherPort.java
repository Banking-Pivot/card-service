package com.banking.cardservice.application.port.out;

import com.banking.cardservice.application.model.VerificationRequest;
import reactor.core.publisher.Mono;

/** Sends questions about accounts to Account Service. */
public interface VerificationPublisherPort {

  /** Completes once the broker has the question; fails when it could not be sent in time. */
  Mono<Void> publish(VerificationRequest request);
}
