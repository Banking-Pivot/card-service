package com.banking.cardservice.application.handler.command;

import com.banking.cardservice.application.command.IssueCardCommand;
import com.banking.cardservice.application.exception.CardNumberTakenException;
import com.banking.cardservice.application.port.in.IssueCardUseCase;
import com.banking.cardservice.application.port.out.CardNumberGenerator;
import com.banking.cardservice.application.port.out.CardStorePort;
import com.banking.cardservice.domain.exception.CardRuleViolationException;
import com.banking.cardservice.domain.exception.RuleViolation;
import com.banking.cardservice.domain.model.Card;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Issues a debit card: stores it waiting for the check of its main account and asks Account Service
 * whether that account belongs to the customer and whether the customer has no overdue debt.
 *
 * <p>A customer has a limited number of cards. The ones that were rejected do not count: they never
 * became cards the customer can use. Neither do the cancelled ones: the customer no longer has
 * them.
 *
 * <p>The customer and the account are not looked up here. This service never calls another one over
 * HTTP; the answer arrives later through the event broker and decides whether the card is active or
 * rejected.
 */
public class IssueCardHandler implements IssueCardUseCase {

  private static final int NUMBER_ATTEMPTS = 5;

  private final CardStorePort cards;
  private final VerificationDispatcher dispatcher;
  private final CardNumberGenerator numbers;
  private final Clock clock;
  private final Supplier<UUID> ids;
  private final int maxCardsPerCustomer;

  /**
   * Creates the handler.
   *
   * @param numbers source of card numbers
   * @param ids source of identifiers for the card and its link
   * @param maxCardsPerCustomer most cards a customer may have, not counting the rejected and the
   *     cancelled ones
   */
  public IssueCardHandler(
      CardStorePort cards,
      VerificationDispatcher dispatcher,
      CardNumberGenerator numbers,
      Clock clock,
      Supplier<UUID> ids,
      int maxCardsPerCustomer) {
    this.cards = Objects.requireNonNull(cards);
    this.dispatcher = Objects.requireNonNull(dispatcher);
    this.numbers = Objects.requireNonNull(numbers);
    this.clock = Objects.requireNonNull(clock);
    this.ids = Objects.requireNonNull(ids);
    this.maxCardsPerCustomer = maxCardsPerCustomer;
  }

  @Override
  public Mono<Card> execute(IssueCardCommand command) {
    // A generated number that another card already has is replaced by a new one.
    return requireRoomFor(command.customerId())
        .then(
            Mono.defer(() -> store(command))
                .retryWhen(
                    Retry.max(NUMBER_ATTEMPTS)
                        .filter(CardNumberTakenException.class::isInstance)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure())))
        .flatMap(card -> dispatcher.dispatch(card, card.accounts().get(0).id()).thenReturn(card));
  }

  private Mono<Void> requireRoomFor(UUID customerId) {
    return cards
        .countHeld(customerId)
        .flatMap(
            held ->
                held >= maxCardsPerCustomer
                    ? Mono.error(
                        new CardRuleViolationException(
                            RuleViolation.CARD_LIMIT_REACHED,
                            "The customer already has the maximum number of cards."))
                    : Mono.empty());
  }

  private Mono<Card> store(IssueCardCommand command) {
    Card card =
        Card.issue(
            ids.get(),
            command.customerId(),
            numbers.next(),
            ids.get(),
            command.mainAccountId(),
            command.correlationId(),
            clock.instant());
    return cards.insert(card).thenReturn(card);
  }
}
