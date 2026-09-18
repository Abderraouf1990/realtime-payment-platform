package com.aayadi.payment.processor.application;

import com.aayadi.payment.processor.domain.LedgerEntry;

public interface LedgerStore {
    /** Returns only after committing a new entry or confirming an identical business payload. */
    Outcome save(LedgerEntry entry);

    enum Outcome { INSERTED, DUPLICATE }
}
