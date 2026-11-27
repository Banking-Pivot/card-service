package com.banking.cardservice.infrastructure.adapter.in.rest;

import com.banking.cardservice.application.model.ResultPage;
import com.banking.cardservice.domain.model.AccountLink;
import com.banking.cardservice.domain.model.Card;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardPageResponse;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardResponse;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.CardStatus;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.LinkStatus;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.LinkedAccount;
import com.banking.cardservice.infrastructure.adapter.in.rest.generated.model.Rejection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * Turns cards into the responses of the contract. Enums are matched by name, and the card number
 * leaves this service masked.
 */
@Component
public class CardRestMapper {

  /** The card as the contract shows it; fields that do not apply are left out. */
  public CardResponse toResponse(Card card) {
    return new CardResponse()
        .id(card.id())
        .customerId(card.customerId())
        .maskedNumber(card.number().masked())
        .status(CardStatus.fromValue(card.status().name()))
        .mainAccountId(card.mainAccountId())
        .accounts(card.accounts().stream().map(CardRestMapper::toAccount).toList())
        .rejection(toRejection(card.rejection()))
        .createdAt(utc(card.createdAt()))
        .updatedAt(utc(card.updatedAt()));
  }

  /** A page of cards with its totals. */
  public CardPageResponse toPage(ResultPage<Card> page) {
    return new CardPageResponse()
        .items(page.items().stream().map(this::toResponse).toList())
        .page(page.page())
        .size(page.size())
        .totalElements(page.totalElements())
        .totalPages(page.totalPages());
  }

  private static LinkedAccount toAccount(AccountLink link) {
    return new LinkedAccount()
        .accountId(link.accountId())
        .position(link.position())
        .main(link.main())
        .status(LinkStatus.fromValue(link.status().name()))
        .currency(link.currency())
        .rejection(toRejection(link.rejection()))
        .linkedAt(utc(link.createdAt()));
  }

  private static Rejection toRejection(
      com.banking.cardservice.domain.model.vo.Rejection rejection) {
    return rejection == null
        ? null
        : new Rejection().code(rejection.code()).message(rejection.message());
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
