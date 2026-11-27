package com.banking.cardservice.application.handler.query;

import com.banking.cardservice.application.model.ResultPage;
import com.banking.cardservice.application.port.in.ListCardsUseCase;
import com.banking.cardservice.application.port.out.CardStorePort;
import com.banking.cardservice.application.query.ListCardsQuery;
import com.banking.cardservice.domain.model.Card;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Lists cards, newest first, optionally those of one customer. */
public class ListCardsHandler implements ListCardsUseCase {

  private final CardStorePort cards;

  public ListCardsHandler(CardStorePort cards) {
    this.cards = Objects.requireNonNull(cards);
  }

  @Override
  public Mono<ResultPage<Card>> execute(ListCardsQuery query) {
    return cards.findPage(query);
  }
}
