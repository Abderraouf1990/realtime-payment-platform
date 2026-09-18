package com.aayadi.payment.processor.domain;

import com.aayadi.payment.contracts.v1.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntry(String transactionId, String correlationId, String accountId,
                          BigDecimal amount, String currency, TransactionType type,
                          Instant receivedAt, Instant processedAt) {
}
