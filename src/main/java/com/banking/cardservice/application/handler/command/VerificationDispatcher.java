package com.banking.cardservice.application.handler.command;

import com.banking.cardservice.application.model.VerificationRequest;
import com.banking.cardservice.application.port.out.VerificationPublisherPort;
import com.banking.cardservice.domain.model.AccountLink;
import com.banking.cardservice.domain.model.Card;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Sends the question about a pending account, after the card was stored with that account.
 *
 * <p>The stored link is what guarantees the question: when sending fails, nothing is lost, because
 * {@link ResendPendingVerificationsHandler} finds the link still pending and asks again. So a
 * failure here is logged and not passed on.
 */
public class VerificationDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(VerificationDispatcher.class);

  private final VerificationPublisherPort publisher;

  public VerificationDispatcher(VerificationPublisherPort publisher) {
    this.publisher = Objects.requireNonNull(publisher);
  }

  /**
   * Asks Account Service about the account of that link, if the link is still pending. The question
   * about the first account of a new card also asks that the customer has no overdue debt.
   *
   * @return completion, also when the question could not be sent
   */
  public Mono<Void> dispatch(Card card, UUID linkId) {
    return Mono.justOrEmpty(card.link(linkId))
        .filter(AccountLink::isPending)
        .flatMap(
            link ->
                publisher
                    .publish(
                        new VerificationRequest(
                            link.id(),
                            link.accountId(),
                            card.customerId(),
                            link.correlationId(),
                            card.isIssuance(link.id())))
                    .onErrorResume(
                        failure -> {
                          LOG.warn(
                              "Account check not sent, it will be sent again. corrId={}, "
                                  + "cardId={}, linkId={}, failureType={}",
                              link.correlationId(),
                              card.id(),
                              link.id(),
                              failure.getClass().getName());
                          return Mono.empty();
                        }));
  }
}
