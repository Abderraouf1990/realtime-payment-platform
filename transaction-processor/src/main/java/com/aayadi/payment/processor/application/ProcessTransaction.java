package com.aayadi.payment.processor.application;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import com.aayadi.payment.processor.domain.LedgerEntry;
import java.time.Clock;

public class ProcessTransaction {
    private final LedgerStore store;
    private final Clock clock;

    public ProcessTransaction(LedgerStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public LedgerStore.Outcome process(TransactionReceived event) {
        if (event == null || event.schemaVersion() != 1
                || !validId(event.transactionId()) || !validId(event.correlationId()) || !validId(event.accountId())
                || event.amount() == null || event.amount().signum() <= 0
                || event.amount().scale() > 2 || event.amount().precision() - event.amount().scale() > 15
                || event.currency() == null || !event.currency().matches("[A-Z]{3}")
                || event.type() == null || event.receivedAt() == null) {
            throw new IllegalArgumentException("Invalid version-1 transaction event");
        }
        return store.save(new LedgerEntry(event.transactionId(), event.correlationId(), event.accountId(),
                event.amount(), event.currency(), event.type(), event.receivedAt(), clock.instant()));
    }

    private static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }
}
