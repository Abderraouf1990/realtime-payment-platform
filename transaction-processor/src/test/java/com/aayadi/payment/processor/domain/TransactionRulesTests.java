package com.aayadi.payment.processor.domain;

import com.aayadi.payment.contracts.v1.TransactionType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import static com.aayadi.payment.processor.domain.TransactionRules.RejectionReason.*;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionRulesTests {
    private final TransactionRules rules = new TransactionRules();

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "999999999999999.99"})
    void acceptsStrictlyPositiveAmounts(String amount) {
        assertThat(rules.validate(new BigDecimal(amount), "EUR", TransactionType.TRANSFER)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "0.00", "-0.01", "-100"})
    void rejectsNonPositiveOrMissingAmounts(String amount) {
        assertThat(rules.validate(amount == null ? null : new BigDecimal(amount), "EUR", TransactionType.TRANSFER))
                .containsExactly(AMOUNT_NOT_POSITIVE);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"USD", "eur", "Eur", " EUR", "EUR ", ""})
    void requiresExactEuroCode(String currency) {
        assertThat(rules.validate(BigDecimal.ONE, currency, TransactionType.TRANSFER))
                .containsExactly(CURRENCY_NOT_EUR);
    }
}
