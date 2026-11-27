package com.banking.cardservice.support;

import com.banking.cardservice.application.model.VerificationRequest;
import com.banking.cardservice.application.port.out.VerificationPublisherPort;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

/** Remembers the questions it was asked to send; it can also refuse to send. */
public final class RecordingPublisher implements VerificationPublisherPort {

  private final List<VerificationRequest> sent = new ArrayList<>();
  private boolean down;

  /** From now on every send fails, as when Kafka cannot be reached. */
  public void goDown() {
    down = true;
  }

  public List<VerificationRequest> sent() {
    return List.copyOf(sent);
  }

  @Override
  public Mono<Void> publish(VerificationRequest request) {
    return Mono.defer(
        () -> {
          if (down) {
            return Mono.error(new IllegalStateException("Kafka is not reachable"));
          }
          sent.add(request);
          return Mono.empty();
        });
  }
}
