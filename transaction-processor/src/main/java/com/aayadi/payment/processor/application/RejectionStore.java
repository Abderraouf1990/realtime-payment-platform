package com.aayadi.payment.processor.application;

import com.aayadi.payment.processor.domain.TransactionRejection;

public interface RejectionStore {
    /** Returns only after committing the audit row or confirming an already durable rejection. */
    void save(TransactionRejection rejection);
}
