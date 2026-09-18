CREATE TABLE ledger_transactions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    amount NUMERIC(17,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    type VARCHAR(32) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_ledger_transactions_transaction_id UNIQUE (transaction_id)
);

-- Account history; the primary key and UNIQUE constraint already provide their indexes.
CREATE INDEX idx_ledger_transactions_account_received_at
    ON ledger_transactions (account_id, received_at DESC);
