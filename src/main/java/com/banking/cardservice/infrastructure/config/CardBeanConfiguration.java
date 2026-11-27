package com.banking.cardservice.infrastructure.config;

import com.banking.cardservice.application.handler.command.ApplyAccountVerificationHandler;
import com.banking.cardservice.application.handler.command.CardUpdater;
import com.banking.cardservice.application.handler.command.IssueCardHandler;
import com.banking.cardservice.application.handler.command.LinkAccountHandler;
import com.banking.cardservice.application.handler.command.ManageCardHandler;
import com.banking.cardservice.application.handler.command.ResendPendingVerificationsHandler;
import com.banking.cardservice.application.handler.command.VerificationDispatcher;
import com.banking.cardservice.application.handler.query.GetCardHandler;
import com.banking.cardservice.application.handler.query.ListCardsHandler;
import com.banking.cardservice.application.handler.query.ResolveCardHandler;
import com.banking.cardservice.application.port.in.ApplyAccountVerificationUseCase;
import com.banking.cardservice.application.port.in.GetCardUseCase;
import com.banking.cardservice.application.port.in.IssueCardUseCase;
import com.banking.cardservice.application.port.in.LinkAccountUseCase;
import com.banking.cardservice.application.port.in.ListCardsUseCase;
import com.banking.cardservice.application.port.in.ManageCardUseCase;
import com.banking.cardservice.application.port.in.ResendPendingVerificationsUseCase;
import com.banking.cardservice.application.port.in.ResolveCardUseCase;
import com.banking.cardservice.application.port.out.CardNumberGenerator;
import com.banking.cardservice.application.port.out.CardStorePort;
import com.banking.cardservice.application.port.out.VerificationPublisherPort;
import com.banking.cardservice.domain.model.CardNumber;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the use cases. The application and domain layers have no Spring annotations; this class is
 * where they meet their adapters.
 */
@Configuration
@EnableConfigurationProperties(CardProperties.class)
public class CardBeanConfiguration {

  @Bean
  public Clock cardClock() {
    return Clock.systemUTC();
  }

  /** Card numbers of the bank with digits nobody can predict. */
  @Bean
  public CardNumberGenerator cardNumberGenerator() {
    SecureRandom random = new SecureRandom();
    return () -> CardNumber.generate(() -> random.nextInt(10));
  }

  @Bean
  public VerificationDispatcher verificationDispatcher(VerificationPublisherPort publisher) {
    return new VerificationDispatcher(publisher);
  }

  @Bean
  public CardUpdater cardUpdater(CardStorePort cards) {
    return new CardUpdater(cards);
  }

  /**
   * Issuing a card, with random identifiers for the card and its first link and the limit of cards
   * per customer of the settings.
   */
  @Bean
  public IssueCardUseCase issueCardUseCase(
      CardStorePort cards,
      VerificationDispatcher dispatcher,
      CardNumberGenerator numbers,
      Clock clock,
      CardProperties properties) {
    return new IssueCardHandler(
        cards,
        dispatcher,
        numbers,
        clock,
        UUID::randomUUID,
        properties.limits().maxCardsPerCustomer());
  }

  @Bean
  public LinkAccountUseCase linkAccountUseCase(
      CardUpdater updater, VerificationDispatcher dispatcher, Clock clock) {
    return new LinkAccountHandler(updater, dispatcher, clock, UUID::randomUUID);
  }

  @Bean
  public ManageCardUseCase manageCardUseCase(CardUpdater updater, Clock clock) {
    return new ManageCardHandler(updater, clock);
  }

  @Bean
  public ApplyAccountVerificationUseCase applyAccountVerificationUseCase(
      CardStorePort cards, Clock clock) {
    return new ApplyAccountVerificationHandler(cards, clock);
  }

  /** The re-send of unanswered questions, with the wait and the batch size of the settings. */
  @Bean
  public ResendPendingVerificationsUseCase resendPendingVerificationsUseCase(
      CardStorePort cards,
      VerificationDispatcher dispatcher,
      Clock clock,
      CardProperties properties) {
    return new ResendPendingVerificationsHandler(
        cards,
        dispatcher,
        clock,
        properties.messaging().resendAfter(),
        properties.messaging().resendBatchSize());
  }

  @Bean
  public ResolveCardUseCase resolveCardUseCase(CardStorePort cards) {
    return new ResolveCardHandler(cards);
  }

  @Bean
  public GetCardUseCase getCardUseCase(CardStorePort cards) {
    return new GetCardHandler(cards);
  }

  @Bean
  public ListCardsUseCase listCardsUseCase(CardStorePort cards) {
    return new ListCardsHandler(cards);
  }
}
