package com.banking.cardservice.domain.model;

import com.banking.cardservice.domain.exception.CardRuleViolationException;
import com.banking.cardservice.domain.exception.LinkedAccountNotFoundException;
import com.banking.cardservice.domain.exception.RuleViolation;
import com.banking.cardservice.domain.model.enums.CardStatus;
import com.banking.cardservice.domain.model.enums.LinkStatus;
import com.banking.cardservice.domain.model.vo.CardNumber;
import com.banking.cardservice.domain.model.vo.HolderDocument;
import com.banking.cardservice.domain.model.vo.Rejection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * A debit card and the bank accounts it charges. Immutable: every change returns a new card with
 * the next version, and the store only writes it if nobody changed the card in between.
 *
 * <p>The card always has exactly one main account. It is the first account until another LINKED
 * account is chosen.
 *
 * @param id identifier of the card
 * @param customerId customer who holds the card
 * @param number the 16-digit number
 * @param status where the card is in its life
 * @param rejection why the card was rejected, when it was
 * @param holderDocument identity document of the customer, known once Account Service confirms the
 *     first account; null for cards issued before it was reported
 * @param accounts the linked accounts, in the order they were linked
 * @param createdAt when the card was issued
 * @param updatedAt when the card or one of its accounts last changed
 * @param version number of changes so far
 */
public record Card(
    UUID id,
    UUID customerId,
    CardNumber number,
    CardStatus status,
    Rejection rejection,
    HolderDocument holderDocument,
    List<AccountLink> accounts,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  /**
   * Everything is required except the rejection and the holder document; the accounts are kept
   * ordered by position.
   */
  public Card {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(customerId, "customerId");
    Objects.requireNonNull(number, "number");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    accounts = accounts.stream().sorted(Comparator.comparingInt(AccountLink::position)).toList();
  }

  /**
   * A new card waiting for Account Service to confirm its main account.
   *
   * @param linkId identifier of the link to the main account
   * @param correlationId correlation identifier of the request
   */
  public static Card issue(
      UUID id,
      UUID customerId,
      CardNumber number,
      UUID linkId,
      UUID mainAccountId,
      String correlationId,
      Instant now) {
    return new Card(
        id,
        customerId,
        number,
        CardStatus.PENDING_VERIFICATION,
        null,
        null,
        List.of(AccountLink.pending(linkId, mainAccountId, 1, true, correlationId, now)),
        now,
        now,
        0);
  }

  /**
   * Adds an account after the ones already linked, waiting for Account Service to confirm it. A
   * link that was rejected before is replaced by the new attempt.
   *
   * @throws CardRuleViolationException when the card is cancelled or not active, or the account is
   *     already linked or being checked
   */
  public Card linkAccount(UUID linkId, UUID accountId, String correlationId, Instant now) {
    requireActive();
    Optional<AccountLink> existing = linkOf(accountId);
    if (existing.isPresent() && existing.get().status() != LinkStatus.REJECTED) {
      throw new CardRuleViolationException(
          RuleViolation.ACCOUNT_ALREADY_LINKED, "The account is already linked to the card.");
    }
    List<AccountLink> changed = new ArrayList<>(accounts);
    existing.ifPresent(changed::remove);
    int next = changed.stream().mapToInt(AccountLink::position).max().orElse(0) + 1;
    changed.add(AccountLink.pending(linkId, accountId, next, false, correlationId, now));
    return with(status, rejection, holderDocument, changed, now);
  }

  /**
   * Applies the confirmation of Account Service: the account is linked and, if it was the first
   * account of a new card, the card becomes active.
   *
   * @return the changed card, or empty when the card is not waiting for that answer
   */
  public Optional<Card> accountVerified(UUID linkId, String currency, Instant now) {
    return accountVerified(linkId, currency, null, now);
  }

  /**
   * Applies the confirmation of Account Service together with the identity document of the customer
   * it reports. The document is kept only when the account is the first one of a new card: that is
   * the answer that says who the holder is.
   *
   * @param holder the document Account Service reported, or null when it reported none
   * @return the changed card, or empty when the card is not waiting for that answer
   */
  public Optional<Card> accountVerified(
      UUID linkId, String currency, HolderDocument holder, Instant now) {
    return pendingLink(linkId)
        .map(
            link ->
                with(
                    activatedBy(link) ? CardStatus.ACTIVE : status,
                    rejection,
                    activatedBy(link) && holder != null ? holder : holderDocument,
                    replace(link.id(), pending -> pending.verified(currency, now)),
                    now));
  }

  /**
   * Applies the refusal of Account Service: the link is rejected and, if it was the first account
   * of a new card, so is the card.
   *
   * @return the changed card, or empty when the card is not waiting for that answer
   */
  public Optional<Card> accountRejected(UUID linkId, Rejection reason, Instant now) {
    return pendingLink(linkId)
        .map(
            link ->
                with(
                    activatedBy(link) ? CardStatus.REJECTED : status,
                    activatedBy(link) ? reason : rejection,
                    holderDocument,
                    replace(link.id(), pending -> pending.rejected(reason, now)),
                    now));
  }

  /**
   * Makes a linked account the one the card charges first. Choosing the current main account
   * changes nothing and returns this same card.
   *
   * @throws CardRuleViolationException when the card is cancelled or not active, or the account is
   *     not LINKED
   */
  public Card changeMainAccount(UUID accountId, Instant now) {
    requireActive();
    AccountLink chosen =
        linkOf(accountId)
            .filter(AccountLink::isLinked)
            .orElseThrow(
                () ->
                    new CardRuleViolationException(
                        RuleViolation.ACCOUNT_NOT_LINKED,
                        "The account is not linked to the card."));
    if (chosen.main()) {
      return this;
    }
    List<AccountLink> changed =
        accounts.stream()
            .map(
                link ->
                    link.main() || link.id().equals(chosen.id())
                        ? link.withMain(link.id().equals(chosen.id()), now)
                        : link)
            .toList();
    return with(status, rejection, holderDocument, changed, now);
  }

  /**
   * Removes an account from the card, whatever the state of its link.
   *
   * @throws LinkedAccountNotFoundException when the card has no link to the account
   * @throws CardRuleViolationException when the card is cancelled or not active, or the account is
   *     the main one
   */
  public Card unlinkAccount(UUID accountId, Instant now) {
    requireActive();
    AccountLink link =
        linkOf(accountId).orElseThrow(() -> new LinkedAccountNotFoundException(id, accountId));
    if (link.main()) {
      throw new CardRuleViolationException(
          RuleViolation.MAIN_ACCOUNT_REQUIRED,
          "The main account cannot be unlinked; choose another main account first.");
    }
    List<AccountLink> changed = new ArrayList<>(accounts);
    changed.remove(link);
    return with(status, rejection, holderDocument, changed, now);
  }

  /**
   * Blocks an active card. A card that is already blocked is returned as it is.
   *
   * @throws CardRuleViolationException when the card is pending, rejected or cancelled
   */
  public Card block(Instant now) {
    if (status == CardStatus.BLOCKED) {
      return this;
    }
    requireActive();
    return with(CardStatus.BLOCKED, rejection, holderDocument, accounts, now);
  }

  /**
   * Unblocks a blocked card. A card that is already active is returned as it is.
   *
   * @throws CardRuleViolationException when the card is pending, rejected or cancelled
   */
  public Card unblock(Instant now) {
    if (status == CardStatus.ACTIVE) {
      return this;
    }
    if (status != CardStatus.BLOCKED) {
      throw notUsable();
    }
    return with(CardStatus.ACTIVE, rejection, holderDocument, accounts, now);
  }

  /**
   * Cancels the card for good, whatever its status. The card keeps its accounts and, when it was
   * rejected, the reason. A card that is already cancelled is returned as it is.
   */
  public Card cancel(Instant now) {
    if (status == CardStatus.CANCELLED) {
      return this;
    }
    return with(CardStatus.CANCELLED, rejection, holderDocument, accounts, now);
  }

  /**
   * Records that the question about a pending account was sent again.
   *
   * @return the changed card, or this same card when that link is not pending
   */
  public Card verificationResent(UUID linkId, Instant now) {
    return pendingLink(linkId)
        .map(
            link ->
                new Card(
                    id,
                    customerId,
                    number,
                    status,
                    rejection,
                    holderDocument,
                    replace(link.id(), pending -> pending.verificationResent(now)),
                    createdAt,
                    updatedAt,
                    version + 1))
        .orElse(this);
  }

  /** The account the card charges first. */
  public UUID mainAccountId() {
    return accounts.stream()
        .filter(AccountLink::main)
        .map(AccountLink::accountId)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("The card has no main account."));
  }

  /** The accounts a payment may charge: the LINKED ones, main first, then in linking order. */
  public List<UUID> chargeableAccountIds() {
    return accounts.stream()
        .filter(AccountLink::isLinked)
        .sorted(Comparator.comparing((AccountLink link) -> !link.main()))
        .map(AccountLink::accountId)
        .toList();
  }

  /** The links still waiting for Account Service. */
  public List<AccountLink> pendingLinks() {
    return accounts.stream().filter(AccountLink::isPending).toList();
  }

  /** The link with that identifier, if the card has it. */
  public Optional<AccountLink> link(UUID linkId) {
    return accounts.stream().filter(link -> link.id().equals(linkId)).findFirst();
  }

  /**
   * Whether that link is the first account of a card that still waits to be issued: the answer
   * about it decides if the customer gets the card.
   */
  public boolean isIssuance(UUID linkId) {
    return link(linkId).filter(this::activatedBy).isPresent();
  }

  private Optional<AccountLink> linkOf(UUID accountId) {
    return accounts.stream().filter(link -> link.accountId().equals(accountId)).findFirst();
  }

  private Optional<AccountLink> pendingLink(UUID linkId) {
    return link(linkId).filter(AccountLink::isPending);
  }

  // The first account of a new card decides whether the card is born active or rejected.
  private boolean activatedBy(AccountLink link) {
    return status == CardStatus.PENDING_VERIFICATION && link.main();
  }

  private List<AccountLink> replace(UUID linkId, UnaryOperator<AccountLink> change) {
    return accounts.stream()
        .map(link -> link.id().equals(linkId) ? change.apply(link) : link)
        .toList();
  }

  private Card with(
      CardStatus newStatus,
      Rejection newRejection,
      HolderDocument newHolderDocument,
      List<AccountLink> newAccounts,
      Instant now) {
    return new Card(
        id,
        customerId,
        number,
        newStatus,
        newRejection,
        newHolderDocument,
        newAccounts,
        createdAt,
        now,
        version + 1);
  }

  private void requireActive() {
    if (status != CardStatus.ACTIVE) {
      throw notUsable();
    }
  }

  // A cancelled card says so; any other card that is not active is just not active.
  private CardRuleViolationException notUsable() {
    return status == CardStatus.CANCELLED
        ? new CardRuleViolationException(RuleViolation.CARD_CANCELLED, "The card is cancelled.")
        : new CardRuleViolationException(RuleViolation.CARD_NOT_ACTIVE, "The card is not active.");
  }
}
