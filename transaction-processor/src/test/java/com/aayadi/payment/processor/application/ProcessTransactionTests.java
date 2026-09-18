package com.aayadi.payment.processor.application;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import com.aayadi.payment.contracts.v1.TransactionType;
import com.aayadi.payment.processor.domain.LedgerEntry;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessTransactionTests {
    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void mapsSharedContractToLedgerAndSetsProcessingTime() {
        List<LedgerEntry> entries = new ArrayList<>();
        var processor = new ProcessTransaction(entry -> { entries.add(entry); return LedgerStore.Outcome.INSERTED; }, CLOCK);
        var event = event(1, new BigDecimal("250.00"));
        assertThat(processor.process(event)).isEqualTo(LedgerStore.Outcome.INSERTED);
        assertThat(entries).containsExactly(new LedgerEntry("TX-1", "CORR-1", "ACC-1",
                new BigDecimal("250.00"), "EUR", TransactionType.TRANSFER, NOW.minusSeconds(1), NOW));
    }

    @Test
    void rejectsInvalidEventsBeforePersistence() {
        var processor = new ProcessTransaction(entry -> { throw new AssertionError("Must not persist"); }, CLOCK);
        for (var invalid : new TransactionReceived[] {null, event(2, BigDecimal.ONE),
                event(1, BigDecimal.ZERO), event(1, new BigDecimal("-1")), event(1, new BigDecimal("1.234")),
                event(1, new BigDecimal("1000000000000000")),
                new TransactionReceived(1, "TX-1", "bad\ncorrelation", "ACC-1", BigDecimal.ONE, "EUR", TransactionType.TRANSFER, NOW)}) {
            assertThatThrownBy(() -> processor.process(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void returnsDuplicateOutcomeAndPropagatesStorageFailure() {
        assertThat(new ProcessTransaction(entry -> LedgerStore.Outcome.DUPLICATE, CLOCK).process(event(1, BigDecimal.ONE)))
                .isEqualTo(LedgerStore.Outcome.DUPLICATE);
        var failure = new IllegalStateException("storage failure");
        var processor = new ProcessTransaction(entry -> { throw failure; }, CLOCK);
        assertThatThrownBy(() -> processor.process(event(1, BigDecimal.ONE))).isSameAs(failure);
    }

    private static TransactionReceived event(int version, BigDecimal amount) {
        return new TransactionReceived(version, "TX-1", "CORR-1", "ACC-1", amount,
                "EUR", TransactionType.TRANSFER, NOW.minusSeconds(1));
    }
}
