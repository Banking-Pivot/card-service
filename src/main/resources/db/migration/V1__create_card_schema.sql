-- Card Service keeps debit cards and the bank accounts linked to them, never a balance: the
-- money is in Account Service. Instants are TIMESTAMPTZ and the application writes them in UTC.
-- Enum columns are VARCHAR with CHECK constraints.

-- One row per debit card.
CREATE TABLE cards (
    id                 UUID         NOT NULL,
    customer_id        UUID         NOT NULL,
    -- 16 digits, the last one the Luhn check digit. The API only shows the last four.
    card_number        VARCHAR(16)  NOT NULL,
    status             VARCHAR(24)  NOT NULL,
    -- Why the card was rejected: the answer of Account Service about its first account.
    rejection_code     VARCHAR(60),
    rejection_message  VARCHAR(300),
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    -- Changes with the card and with any of its accounts.
    version            BIGINT       NOT NULL,
    CONSTRAINT pk_cards PRIMARY KEY (id),
    CONSTRAINT uk_cards_number UNIQUE (card_number),
    CONSTRAINT ck_cards_number CHECK (card_number ~ '^[0-9]{16}$'),
    CONSTRAINT ck_cards_status CHECK (
        status IN ('PENDING_VERIFICATION', 'ACTIVE', 'REJECTED', 'BLOCKED')),
    CONSTRAINT ck_cards_rejection CHECK (
        (rejection_code IS NOT NULL) = (status = 'REJECTED')
        AND (rejection_code IS NULL) = (rejection_message IS NULL)),
    CONSTRAINT ck_cards_version CHECK (version >= 0)
);

CREATE INDEX ix_cards_customer ON cards (customer_id);
CREATE INDEX ix_cards_created ON cards (created_at DESC, id);

-- One row per account linked to a card. The ID is also the transactionId of the question
-- Account Service is asked about the account.
CREATE TABLE card_accounts (
    id                     UUID         NOT NULL,
    card_id                UUID         NOT NULL,
    account_id             UUID         NOT NULL,
    -- Order the accounts were linked in: payments try the main account and then the rest
    -- in this order.
    link_position          INTEGER      NOT NULL,
    main_account           BOOLEAN      NOT NULL,
    status                 VARCHAR(10)  NOT NULL,
    -- Currency of the account, known once Account Service confirms it.
    currency               VARCHAR(3),
    rejection_code         VARCHAR(60),
    rejection_message      VARCHAR(300),
    -- Correlation identifier of the request, written in the question to Account Service.
    correlation_id         VARCHAR(64)  NOT NULL,
    -- When the question was last sent and how many times it was repeated.
    verification_sent_at   TIMESTAMPTZ  NOT NULL,
    verification_attempts  INTEGER      NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_card_accounts PRIMARY KEY (id),
    CONSTRAINT fk_card_accounts_card FOREIGN KEY (card_id) REFERENCES cards (id),
    CONSTRAINT uk_card_accounts_card_account UNIQUE (card_id, account_id),
    CONSTRAINT uk_card_accounts_card_position UNIQUE (card_id, link_position),
    CONSTRAINT ck_card_accounts_status CHECK (status IN ('PENDING', 'LINKED', 'REJECTED')),
    CONSTRAINT ck_card_accounts_position CHECK (link_position >= 1),
    CONSTRAINT ck_card_accounts_linked CHECK ((currency IS NOT NULL) = (status = 'LINKED')),
    CONSTRAINT ck_card_accounts_rejection CHECK (
        (rejection_code IS NOT NULL) = (status = 'REJECTED')
        AND (rejection_code IS NULL) = (rejection_message IS NULL)),
    CONSTRAINT ck_card_accounts_attempts CHECK (verification_attempts >= 0)
);

-- A card has one main account.
CREATE UNIQUE INDEX uk_card_accounts_main ON card_accounts (card_id) WHERE main_account;

-- Accounts still waiting for Account Service, oldest question first: what the periodic
-- re-send reads.
CREATE INDEX ix_card_accounts_pending ON card_accounts (verification_sent_at)
    WHERE status = 'PENDING';
