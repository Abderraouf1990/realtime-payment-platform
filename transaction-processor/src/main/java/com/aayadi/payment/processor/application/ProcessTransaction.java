package com.aayadi.payment.processor.application;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import com.aayadi.payment.processor.domain.LedgerEntry;
import com.aayadi.payment.processor.domain.TransactionRules;
import java.time.Clock;

public class ProcessTransaction {
    private final LedgerStore store;
    private final Clock clock;
    private final TransactionRules rules;

    public ProcessTransaction(LedgerStore store, Clock clock, TransactionRules rules) {
        this.store = store;
        this.clock = clock;
        this.rules = rules;
    }

    public ProcessingResult process(TransactionReceived event) {
        if (event == null || event.schemaVersion() != 1
                || !validId(event.transactionId()) || !validId(event.correlationId()) || !validId(event.accountId())
                || event.receivedAt() == null) {
            throw new IllegalArgumentException("Invalid version-1 transaction event");
        }
        var reasons = rules.validate(event.amount(), event.currency(), event.type());
        if (!reasons.isEmpty()) {
            return new ProcessingResult.Rejected(reasons);
        }
        if (event.amount().scale() > 2 || event.amount().precision() - event.amount().scale() > 15) {
            throw new IllegalArgumentException("Invalid version-1 amount representation");
        }
        return new ProcessingResult.Accepted(store.save(new LedgerEntry(event.transactionId(), event.correlationId(), event.accountId(),
                event.amount(), event.currency(), event.type(), event.receivedAt(), clock.instant())));
    }

    private static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }
}
