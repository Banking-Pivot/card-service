package com.banking.cardservice.application.port.in;

import com.banking.cardservice.application.model.ResultPage;
import com.banking.cardservice.application.query.ListCardsQuery;
import com.banking.cardservice.domain.model.Card;
import reactor.core.publisher.Mono;

/** Lists cards, newest first. */
public interface ListCardsUseCase {

  Mono<ResultPage<Card>> execute(ListCardsQuery query);
}
