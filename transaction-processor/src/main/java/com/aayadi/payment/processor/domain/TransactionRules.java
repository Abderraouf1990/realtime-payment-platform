package com.aayadi.payment.processor.domain;

import com.aayadi.payment.contracts.v1.TransactionType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Pure, deterministic rules; all violations are returned in rule order. */
public final class TransactionRules {
    public enum RejectionReason {
        AMOUNT_NOT_POSITIVE, CURRENCY_NOT_EUR, TYPE_NOT_TRANSFER, PAYLOAD_CONFLICT
    }

    public List<RejectionReason> validate(BigDecimal amount, String currency, TransactionType type) {
        var reasons = new ArrayList<RejectionReason>();
        if (amount == null || amount.signum() <= 0) {
            reasons.add(RejectionReason.AMOUNT_NOT_POSITIVE);
        }
        if (!"EUR".equals(currency)) {
            reasons.add(RejectionReason.CURRENCY_NOT_EUR);
        }
        if (type != TransactionType.TRANSFER) {
            reasons.add(RejectionReason.TYPE_NOT_TRANSFER);
        }
        return List.copyOf(reasons);
    }
}
