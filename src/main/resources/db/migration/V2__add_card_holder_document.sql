-- Identity document of the customer who holds the card, as Account Service reports it when it
-- confirms the first account. Yanki Service compares it with the document of a wallet before it
-- links the wallet to the card. Cards issued before this change have none.
ALTER TABLE cards
    ADD COLUMN holder_document_type    VARCHAR(24),
    ADD COLUMN holder_document_number  VARCHAR(32),
    ADD CONSTRAINT ck_cards_holder_document CHECK (
        (holder_document_type IS NULL) = (holder_document_number IS NULL));
