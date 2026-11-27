-- A card can be cancelled for good: its row stays, with the status CANCELLED, so the card and
-- its accounts remain readable. A card that was rejected keeps its reason when it is cancelled,
-- so a rejection is allowed on a cancelled card too; a rejected card still always has one.
ALTER TABLE cards
    DROP CONSTRAINT ck_cards_status,
    DROP CONSTRAINT ck_cards_rejection,
    ADD CONSTRAINT ck_cards_status CHECK (
        status IN ('PENDING_VERIFICATION', 'ACTIVE', 'REJECTED', 'BLOCKED', 'CANCELLED')),
    ADD CONSTRAINT ck_cards_rejection CHECK (
        (status <> 'REJECTED' OR rejection_code IS NOT NULL)
        AND (rejection_code IS NULL OR status IN ('REJECTED', 'CANCELLED'))
        AND (rejection_code IS NULL) = (rejection_message IS NULL));
