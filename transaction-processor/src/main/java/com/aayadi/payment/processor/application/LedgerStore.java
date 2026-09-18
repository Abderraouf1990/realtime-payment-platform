package com.aayadi.payment.processor.application;

import com.aayadi.payment.processor.domain.LedgerEntry;
import com.aayadi.payment.processor.domain.BusinessPayload;
import java.util.Optional;

public interface LedgerStore {
    Optional<BusinessPayload> findPayload(String transactionId);

    /** Returns after the transaction completes, including duplicate/conflict comparison. */
    Outcome save(LedgerEntry entry);

    enum Outcome { INSERTED, DUPLICATE, CONFLICT }
}
