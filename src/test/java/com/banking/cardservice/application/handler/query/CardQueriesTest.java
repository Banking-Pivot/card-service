package com.banking.cardservice.application.handler.query;

import static com.banking.cardservice.support.Cards.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.cardservice.application.exception.CardNotFoundException;
import com.banking.cardservice.application.model.CardResolution;
import com.banking.cardservice.application.model.ResultPage;
import com.banking.cardservice.application.query.ListCardsQuery;
import com.banking.cardservice.application.query.PageRequest;
import com.banking.cardservice.domain.model.Card;
import com.banking.cardservice.domain.model.vo.HolderDocument;
import com.banking.cardservice.domain.model.vo.Rejection;
import com.banking.cardservice.support.Cards;
import com.banking.cardservice.support.InMemoryCardStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CardQueriesTest {

  private static final UUID CARD = id(1);
  private static final UUID MAIN_ACCOUNT = id(21);
  private static final UUID SECOND_ACCOUNT = id(22);

  private final InMemoryCardStore store = new InMemoryCardStore();

  @Test
  void aCardIsReadByItsIdentifier() {
    Card card = Cards.active(CARD, id(11), MAIN_ACCOUNT);
    store.put(card);

    assertThat(new GetCardHandler(store).execute(CARD).block()).isEqualTo(card);
    assertThatThrownBy(() -> new GetCardHandler(store).execute(id(404)).block())
        .isInstanceOf(CardNotFoundException.class);
  }

  @Test
  void cardsAreListedByPageAndByCustomer() {
    store.put(Cards.active(CARD, id(11), MAIN_ACCOUNT));
    store.put(Cards.pending(id(2), id(12), SECOND_ACCOUNT));
    ListCardsHandler handler = new ListCardsHandler(store);

    ResultPage<Card> first =
        handler.execute(new ListCardsQuery(new PageRequest(0, 1), Cards.CUSTOMER)).block();
    ResultPage<Card> others =
        handler.execute(new ListCardsQuery(PageRequest.of(null, null), id(77))).block();

    assertThat(first.items()).hasSize(1);
    assertThat(first.totalElements()).isEqualTo(2);
    assertThat(first.totalPages()).isEqualTo(2);
    assertThat(others.items()).isEmpty();
    assertThatThrownBy(() -> new ListCardsQuery(null, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void anActiveCardResolvesToItsLinkedAccountsWithTheMainOneFirst() {
    store.put(
        Cards.withTwoAccounts(CARD, id(11), MAIN_ACCOUNT, id(12), SECOND_ACCOUNT)
            .linkAccount(id(13), id(23), "corr", Cards.START)
            .changeMainAccount(SECOND_ACCOUNT, Cards.START));

    CardResolution resolution = new ResolveCardHandler(store).execute(CARD).block();

    // The third account still waits for its answer: it is not charged.
    assertThat(resolution)
        .isEqualTo(
            new CardResolution.Resolved(
                CARD, Cards.CUSTOMER, SECOND_ACCOUNT, List.of(SECOND_ACCOUNT, MAIN_ACCOUNT), null));
  }

  @Test
  void aCardThatDoesNotExistOrIsNotActiveIsRefused() {
    store.put(Cards.pending(CARD, id(11), MAIN_ACCOUNT));
    store.put(Cards.active(id(2), id(12), MAIN_ACCOUNT).block(Cards.START));
    store.put(
        Cards.pending(id(3), id(13), MAIN_ACCOUNT)
            .accountRejected(id(13), new Rejection("ACCOUNT_NOT_FOUND", "Unknown."), Cards.START)
            .orElseThrow());
    ResolveCardHandler handler = new ResolveCardHandler(store);

    assertThat(handler.execute(id(404)).block())
        .isEqualTo(
            new CardResolution.Refused(id(404), "CARD_NOT_FOUND", "The card does not exist."));
    for (UUID notActive : List.of(CARD, id(2), id(3))) {
      assertThat(handler.execute(notActive).block())
          .isEqualTo(
              new CardResolution.Refused(notActive, "CARD_NOT_ACTIVE", "The card is not active."));
    }
  }

  @Test
  void anActiveCardResolvesWithTheDocumentOfItsHolderWhenItKnowsIt() {
    HolderDocument document = new HolderDocument("DNI", "45871236");
    store.put(
        Cards.pending(CARD, id(11), MAIN_ACCOUNT)
            .accountVerified(id(11), "PEN", document, Cards.START)
            .orElseThrow());

    assertThat(new ResolveCardHandler(store).execute(CARD).block())
        .isEqualTo(
            new CardResolution.Resolved(
                CARD, Cards.CUSTOMER, MAIN_ACCOUNT, List.of(MAIN_ACCOUNT), document));
  }
}
