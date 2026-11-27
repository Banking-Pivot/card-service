package com.banking.cardservice.infrastructure.adapter.in.rest;

import com.banking.cardservice.application.command.IssueCardCommand;
import com.banking.cardservice.application.command.LinkAccountCommand;
import com.banking.cardservice.application.port.in.GetCardUseCase;
import com.banking.cardservice.application.port.in.IssueCardUseCase;
import com.banking.cardservice.application.port.in.LinkAccountUseCase;
import com.banking.cardservice.application.port.in.ListCardsUseCase;
import com.banking.cardservice.application.port.in.ManageCardUseCase;
import com.banking.cardservice.application.query.ListCardsQuery;
import com.banking.cardservice.application.query.PageRequest;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.api.CardsApiDelegate;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.AccountRequest;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardPageResponse;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardResponse;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.IssueCardRequest;
import com.banking.cardservice.infrastructure.correlation.CorrelationId;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Requests and queries of debit cards. Issuing a card and linking an account are answered 202 as
 * soon as they are recorded: Account Service confirms or refuses the account later, and the result
 * is read from the card.
 */
@Component
public class CardsApiDelegateImpl implements CardsApiDelegate {

  static final String CARDS_PATH = "/api/v1/cards/";

  private final IssueCardUseCase issueCard;
  private final LinkAccountUseCase linkAccount;
  private final ManageCardUseCase manageCard;
  private final GetCardUseCase getCard;
  private final ListCardsUseCase listCards;
  private final CardRestMapper mapper;

  /** Creates the delegate with the use cases and the mapper that builds the responses. */
  public CardsApiDelegateImpl(
      IssueCardUseCase issueCard,
      LinkAccountUseCase linkAccount,
      ManageCardUseCase manageCard,
      GetCardUseCase getCard,
      ListCardsUseCase listCards,
      CardRestMapper mapper) {
    this.issueCard = issueCard;
    this.linkAccount = linkAccount;
    this.manageCard = manageCard;
    this.getCard = getCard;
    this.listCards = listCards;
    this.mapper = mapper;
  }

  @Override
  public Mono<ResponseEntity<CardResponse>> issueCard(
      Mono<IssueCardRequest> request, ServerWebExchange exchange) {
    return request
        .map(
            body ->
                new IssueCardCommand(
                    body.getCustomerId(), body.getMainAccountId(), CorrelationId.of(exchange)))
        .flatMap(issueCard::execute)
        .map(
            card ->
                ResponseEntity.accepted()
                    .location(URI.create(CARDS_PATH + card.id()))
                    .body(mapper.toResponse(card)));
  }

  @Override
  public Mono<ResponseEntity<CardResponse>> getCard(UUID cardId, ServerWebExchange exchange) {
    return getCard.execute(cardId).map(mapper::toResponse).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<CardPageResponse>> listCards(
      Integer page, Integer size, UUID customerId, ServerWebExchange exchange) {
    return Mono.fromCallable(() -> new ListCardsQuery(PageRequest.of(page, size), customerId))
        .flatMap(listCards::execute)
        .map(mapper::toPage)
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<CardResponse>> linkAccount(
      UUID cardId, Mono<AccountRequest> request, ServerWebExchange exchange) {
    return request
        .map(
            body -> new LinkAccountCommand(cardId, body.getAccountId(), CorrelationId.of(exchange)))
        .flatMap(linkAccount::execute)
        .map(card -> ResponseEntity.accepted().body(mapper.toResponse(card)));
  }

  @Override
  public Mono<ResponseEntity<CardResponse>> changeMainAccount(
      UUID cardId, Mono<AccountRequest> request, ServerWebExchange exchange) {
    return request
        .flatMap(body -> manageCard.changeMainAccount(cardId, body.getAccountId()))
        .map(mapper::toResponse)
        .map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<Void>> unlinkAccount(
      UUID cardId, UUID accountId, ServerWebExchange exchange) {
    return manageCard
        .unlinkAccount(cardId, accountId)
        .thenReturn(ResponseEntity.noContent().<Void>build());
  }

  @Override
  public Mono<ResponseEntity<CardResponse>> blockCard(UUID cardId, ServerWebExchange exchange) {
    return manageCard.block(cardId).map(mapper::toResponse).map(ResponseEntity::ok);
  }

  @Override
  public Mono<ResponseEntity<CardResponse>> unblockCard(UUID cardId, ServerWebExchange exchange) {
    return manageCard.unblock(cardId).map(mapper::toResponse).map(ResponseEntity::ok);
  }
}
