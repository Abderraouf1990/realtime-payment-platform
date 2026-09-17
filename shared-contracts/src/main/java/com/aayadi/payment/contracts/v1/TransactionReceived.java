package com.aayadi.payment.contracts.v1;

import java.math.BigDecimal;
import java.time.Instant;

/** Version 1 of the transaction intake event. Contains no processing decision. */
public record TransactionReceived(
        int schemaVersion,
        String transactionId,
        String correlationId,
        String accountId,
        BigDecimal amount,
        String currency,
        TransactionType type,
        Instant receivedAt) {
}
