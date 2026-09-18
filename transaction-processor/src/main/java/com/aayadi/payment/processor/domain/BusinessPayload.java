package com.aayadi.payment.processor.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** Business identity only: correlation IDs and timestamps deliberately do not belong here. */
public record BusinessPayload(String accountId, BigDecimal amount, String currency, String type) {
    public boolean matches(BusinessPayload other) {
        return other != null
                && Objects.equals(accountId, other.accountId)
                && amount != null && other.amount != null && amount.compareTo(other.amount) == 0
                && Objects.equals(currency, other.currency)
                && Objects.equals(type, other.type);
    }
}
