package com.aayadi.payment.processor.application;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import com.aayadi.payment.processor.domain.LedgerEntry;
import com.aayadi.payment.processor.domain.TransactionRules;
import com.aayadi.payment.processor.domain.BusinessPayload;
import com.aayadi.payment.processor.domain.TransactionRejection;
import java.util.List;
import java.time.Clock;

public class ProcessTransaction {
    private final LedgerStore store;
    private final Clock clock;
    private final TransactionRules rules;
    private final RejectionStore rejections;

    public ProcessTransaction(LedgerStore store, Clock clock, TransactionRules rules, RejectionStore rejections) {
        this.store = store;
        this.clock = clock;
        this.rules = rules;
        this.rejections = rejections;
    }

    public ProcessingResult process(TransactionReceived event) {
        if (event == null || event.schemaVersion() != 1
                || !validId(event.transactionId()) || !validId(event.correlationId()) || !validId(event.accountId())
                || event.receivedAt() == null) {
            throw new IllegalArgumentException("Invalid version-1 transaction event");
        }
        var payload = new BusinessPayload(event.accountId(), event.amount(), event.currency(),
                event.type() == null ? null : event.type().name());
        var existing = store.findPayload(event.transactionId());
        if (existing.isPresent()) {
            return existing.get().matches(payload)
                    ? new ProcessingResult.Accepted(LedgerStore.Outcome.DUPLICATE) : conflict(event);
        }
        var reasons = rules.validate(event.amount(), event.currency(), event.type());
        if (!reasons.isEmpty()) {
            return reject(event, reasons);
        }
        if (event.amount().scale() > 2 || event.amount().precision() - event.amount().scale() > 15) {
            throw new IllegalArgumentException("Invalid version-1 amount representation");
        }
        var outcome = store.save(new LedgerEntry(event.transactionId(), event.correlationId(), event.accountId(),
                event.amount(), event.currency(), event.type(), event.receivedAt(), clock.instant()));
        return outcome == LedgerStore.Outcome.CONFLICT ? conflict(event) : new ProcessingResult.Accepted(outcome);
    }

    private ProcessingResult.Rejected conflict(TransactionReceived event) {
        return reject(event, List.of(TransactionRules.RejectionReason.PAYLOAD_CONFLICT));
    }

    private ProcessingResult.Rejected reject(TransactionReceived event, List<TransactionRules.RejectionReason> reasons) {
        rejections.save(new TransactionRejection(event.transactionId(), event.correlationId(), event.accountId(),
                event.amount(), event.currency(), event.type(), event.receivedAt(), clock.instant(), reasons));
        return new ProcessingResult.Rejected(reasons);
    }

    private static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }
}
