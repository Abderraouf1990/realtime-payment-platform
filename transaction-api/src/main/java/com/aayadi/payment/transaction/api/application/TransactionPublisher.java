package com.aayadi.payment.transaction.api.application;

import com.aayadi.payment.contracts.v1.TransactionReceived;

public interface TransactionPublisher {
    /** Returns only after confirmed publication, or throws PublicationException. */
    void publish(TransactionReceived event);
}
