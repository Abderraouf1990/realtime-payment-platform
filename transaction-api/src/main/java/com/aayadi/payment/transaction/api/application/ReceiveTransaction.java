package com.aayadi.payment.transaction.api.application;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import com.aayadi.payment.contracts.v1.TransactionType;

import java.math.BigDecimal;
import java.time.Clock;

public class ReceiveTransaction {
    private final TransactionPublisher publisher;
    private final Clock clock;

    public ReceiveTransaction(TransactionPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    public String receive(String transactionId, String correlationId, String accountId, BigDecimal amount,
                          String currency, TransactionType type) {
        publisher.publish(new TransactionReceived(1, transactionId, correlationId, accountId, amount,
                currency, type, clock.instant()));
        return transactionId;
    }
}
