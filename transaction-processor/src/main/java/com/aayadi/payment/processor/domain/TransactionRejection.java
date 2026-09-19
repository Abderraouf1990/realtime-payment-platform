package com.aayadi.payment.processor.domain;

import com.aayadi.payment.contracts.v1.TransactionType;
import com.aayadi.payment.processor.domain.TransactionRules.RejectionReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TransactionRejection(String transactionId, String correlationId, String accountId,
                                   BigDecimal amount, String currency, TransactionType type,
                                   Instant receivedAt, Instant rejectedAt, List<RejectionReason> reasonCodes) {
    public TransactionRejection {
        reasonCodes = List.copyOf(reasonCodes);
        if (reasonCodes.isEmpty()) throw new IllegalArgumentException("A rejection requires a reason");
    }
}
