package com.aayadi.payment.processor.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class BusinessPayloadTests {
    private final BusinessPayload original = new BusinessPayload("ACC-1", new BigDecimal("10.00"), "EUR", "TRANSFER");

    @Test
    void comparesAmountsNumerically() {
        assertThat(original.matches(original)).isTrue();
        assertThat(original.matches(new BusinessPayload("ACC-1", new BigDecimal("10.0"), "EUR", "TRANSFER"))).isTrue();
    }

    @Test
    void detectsEveryBusinessFieldDifferenceAndCombinedDifferences() {
        for (var changed : new BusinessPayload[] {
                new BusinessPayload("ACC-2", new BigDecimal("10.00"), "EUR", "TRANSFER"),
                new BusinessPayload("ACC-1", new BigDecimal("10.01"), "EUR", "TRANSFER"),
                new BusinessPayload("ACC-1", new BigDecimal("10.00"), "USD", "TRANSFER"),
                new BusinessPayload("ACC-1", new BigDecimal("10.00"), "EUR", "OTHER"),
                new BusinessPayload("ACC-2", new BigDecimal("99.00"), "USD", "OTHER"),
                new BusinessPayload("ACC-1", null, "EUR", "TRANSFER"),
                new BusinessPayload("ACC-1", new BigDecimal("10.00"), "EUR", null)}) {
            assertThat(original.matches(changed)).isFalse();
        }
    }
}
