CREATE TABLE transaction_rejections (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    -- Preserve rejected values without rounding; missing business fields are auditable.
    amount NUMERIC,
    currency TEXT,
    type VARCHAR(32),
    received_at TIMESTAMPTZ NOT NULL,
    rejected_at TIMESTAMPTZ NOT NULL,
    reason_codes TEXT[] NOT NULL,
    CONSTRAINT ck_transaction_rejections_reasons CHECK (
        cardinality(reason_codes) > 0 AND array_position(reason_codes, NULL) IS NULL
    ),
    CONSTRAINT uk_transaction_rejections_business_event
        UNIQUE NULLS NOT DISTINCT (transaction_id, account_id, amount, currency, type)
);

-- The unique index starts with transaction_id and already supports lookup by that ID.
CREATE INDEX idx_transaction_rejections_rejected_at ON transaction_rejections (rejected_at);
