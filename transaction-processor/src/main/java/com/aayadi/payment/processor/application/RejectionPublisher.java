package com.aayadi.payment.processor.application;

import com.aayadi.payment.contracts.v1.TransactionRejected;

public interface RejectionPublisher {
    /** Returns after broker confirmation; uncertain or failed publication throws. */
    void publish(TransactionRejected event);
}
