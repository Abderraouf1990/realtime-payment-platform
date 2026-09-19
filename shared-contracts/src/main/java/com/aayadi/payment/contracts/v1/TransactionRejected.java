package com.aayadi.payment.contracts.v1;

import java.time.Instant;
import java.util.List;

/** Version 1 rejection notification. Reason codes are stable wire strings, not Java types. */
public record TransactionRejected(int schemaVersion, String transactionId, String correlationId,
                                  List<String> reasonCodes, Instant rejectedAt) {
    public TransactionRejected {
        reasonCodes = List.copyOf(reasonCodes);
    }
}
