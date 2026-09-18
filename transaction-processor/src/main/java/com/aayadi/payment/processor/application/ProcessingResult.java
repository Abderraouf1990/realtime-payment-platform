package com.aayadi.payment.processor.application;

import com.aayadi.payment.processor.domain.TransactionRules.RejectionReason;
import java.util.List;
import java.util.Objects;

public sealed interface ProcessingResult {
    record Accepted(LedgerStore.Outcome outcome) implements ProcessingResult {
        public Accepted {
            Objects.requireNonNull(outcome);
        }
    }

    record Rejected(List<RejectionReason> reasons) implements ProcessingResult {
        public Rejected {
            reasons = List.copyOf(reasons);
            if (reasons.isEmpty()) {
                throw new IllegalArgumentException("A rejection requires at least one reason");
            }
        }
    }
}
