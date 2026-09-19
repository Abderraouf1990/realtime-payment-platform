package com.aayadi.payment.processor.application;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import com.aayadi.payment.contracts.v1.TransactionRejected;
import com.aayadi.payment.contracts.v1.TransactionType;
import com.aayadi.payment.processor.domain.LedgerEntry;
import com.aayadi.payment.processor.domain.TransactionRules;
import com.aayadi.payment.processor.domain.BusinessPayload;
import com.aayadi.payment.processor.domain.TransactionRejection;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void evaluatesEveryCombinationBeforeAnyLedgerWrite(int violations) {
        List<LedgerEntry> entries = new ArrayList<>();
        List<TransactionRejection> rejections = new ArrayList<>();
        var processor = new ProcessTransaction(store(entry -> { entries.add(entry); return LedgerStore.Outcome.INSERTED; }),
                CLOCK, new TransactionRules(), rejections::add, notification -> {});
        var expected = new ArrayList<TransactionRules.RejectionReason>();
        if ((violations & 1) != 0) expected.add(TransactionRules.RejectionReason.AMOUNT_NOT_POSITIVE);
        if ((violations & 2) != 0) expected.add(TransactionRules.RejectionReason.CURRENCY_NOT_EUR);
        if ((violations & 4) != 0) expected.add(TransactionRules.RejectionReason.TYPE_NOT_TRANSFER);
        // TRANSFER is currently the only enum constant; null exercises the unsupported-type rule.
        var event = new TransactionReceived(1, "TX-1", "CORR-1", "ACC-1",
                (violations & 1) == 0 ? BigDecimal.ONE : BigDecimal.ZERO,
                (violations & 2) == 0 ? "EUR" : "USD",
                (violations & 4) == 0 ? TransactionType.TRANSFER : null, NOW);
        var result = processor.process(event);
        if (expected.isEmpty()) {
            assertThat(result).isEqualTo(new ProcessingResult.Accepted(LedgerStore.Outcome.INSERTED));
            assertThat(entries).hasSize(1);
            assertThat(rejections).isEmpty();
        } else {
            assertThat(result).isEqualTo(new ProcessingResult.Rejected(expected));
            assertThat(entries).isEmpty();
            assertThat(rejections).containsExactly(new TransactionRejection(event.transactionId(), event.correlationId(),
                    event.accountId(), event.amount(), event.currency(), event.type(), event.receivedAt(), NOW, expected));
        }
    }

    @Test
    void mapsSharedContractToLedgerAndSetsProcessingTime() {
        List<LedgerEntry> entries = new ArrayList<>();
        var processor = new ProcessTransaction(store(entry -> { entries.add(entry); return LedgerStore.Outcome.INSERTED; }), CLOCK, new TransactionRules(), rejection -> {}, notification -> { throw new AssertionError("Must not publish"); });
        var event = event(1, new BigDecimal("250.00"));
        assertThat(processor.process(event)).isEqualTo(new ProcessingResult.Accepted(LedgerStore.Outcome.INSERTED));
        assertThat(entries).containsExactly(new LedgerEntry("TX-1", "CORR-1", "ACC-1",
                new BigDecimal("250.00"), "EUR", TransactionType.TRANSFER, NOW.minusSeconds(1), NOW));
    }

    @Test
    void rejectsInvalidEventsBeforePersistence() {
        var processor = new ProcessTransaction(store(entry -> { throw new AssertionError("Must not persist"); }), CLOCK, new TransactionRules(), rejection -> {}, notification -> { throw new AssertionError("Must not publish"); });
        for (var invalid : new TransactionReceived[] {null, event(2, BigDecimal.ONE),
                event(1, new BigDecimal("1.234")),
                event(1, new BigDecimal("1000000000000000")),
                new TransactionReceived(1, "TX-1", "bad\ncorrelation", "ACC-1", BigDecimal.ONE, "EUR", TransactionType.TRANSFER, NOW)}) {
            assertThatThrownBy(() -> processor.process(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void returnsDuplicateOutcomeAndPropagatesStorageFailure() {
        assertThat(new ProcessTransaction(store(entry -> LedgerStore.Outcome.DUPLICATE), CLOCK, new TransactionRules(), rejection -> {}, notification -> { throw new AssertionError("Must not publish"); }).process(event(1, BigDecimal.ONE)))
                .isEqualTo(new ProcessingResult.Accepted(LedgerStore.Outcome.DUPLICATE));
        var failure = new IllegalStateException("storage failure");
        var processor = new ProcessTransaction(store(entry -> { throw failure; }), CLOCK, new TransactionRules(), rejection -> {}, notification -> { throw new AssertionError("Must not publish"); });
        assertThatThrownBy(() -> processor.process(event(1, BigDecimal.ONE))).isSameAs(failure);
    }

    private static TransactionReceived event(int version, BigDecimal amount) {
        return new TransactionReceived(version, "TX-1", "CORR-1", "ACC-1", amount,
                "EUR", TransactionType.TRANSFER, NOW.minusSeconds(1));
    }

    @Test
    void mapsInsertRaceConflictToBusinessRejection() {
        List<TransactionRejection> rejections = new ArrayList<>();
        var processor = new ProcessTransaction(store(entry -> LedgerStore.Outcome.CONFLICT), CLOCK, new TransactionRules(), rejections::add, notification -> {});
        assertThat(processor.process(event(1, BigDecimal.ONE)))
                .isEqualTo(new ProcessingResult.Rejected(List.of(TransactionRules.RejectionReason.PAYLOAD_CONFLICT)));
        assertThat(rejections).singleElement().satisfies(rejection -> {
            assertThat(rejection.transactionId()).isEqualTo("TX-1");
            assertThat(rejection.reasonCodes()).containsExactly(TransactionRules.RejectionReason.PAYLOAD_CONFLICT);
        });
    }

    @Test
    void rejectionPersistenceFailurePropagatesInsteadOfReturningRejected() {
        var failure = new IllegalStateException("rejection commit failed");
        var processor = new ProcessTransaction(store(entry -> { throw new AssertionError("No ledger write"); }),
                CLOCK, new TransactionRules(), rejection -> { throw failure; }, notification -> { throw new AssertionError("Must not publish"); });
        assertThatThrownBy(() -> processor.process(event(1, BigDecimal.ZERO))).isSameAs(failure);
    }

    @Test
    void persistsLookupConflictBeforeReturningAndPropagatesRejectionFailure() {
        var failure = new IllegalStateException("rejection commit failed");
        var existing = new LedgerStore() {
            public Optional<BusinessPayload> findPayload(String id) {
                return Optional.of(new BusinessPayload("ACC-1", BigDecimal.TEN, "EUR", "TRANSFER"));
            }
            public Outcome save(LedgerEntry entry) { throw new AssertionError("Never change existing ledger"); }
        };
        List<TransactionRejection> rejections = new ArrayList<>();
        var processor = new ProcessTransaction(existing, CLOCK, new TransactionRules(), rejections::add, notification -> {});
        assertThat(processor.process(event(1, BigDecimal.ONE)))
                .isEqualTo(new ProcessingResult.Rejected(List.of(TransactionRules.RejectionReason.PAYLOAD_CONFLICT)));
        assertThat(rejections).hasSize(1);
        var failing = new ProcessTransaction(existing, CLOCK, new TransactionRules(), rejection -> { throw failure; }, notification -> { throw new AssertionError("Must not publish"); });
        assertThatThrownBy(() -> failing.process(event(1, BigDecimal.ONE))).isSameAs(failure);
    }

    private static LedgerStore store(Function<LedgerEntry, LedgerStore.Outcome> save) {
        return new LedgerStore() {
            public Optional<BusinessPayload> findPayload(String id) { return Optional.empty(); }
            public Outcome save(LedgerEntry entry) { return save.apply(entry); }
        };
    }

    @Test
    void publishesMappedNotificationOnlyAfterPersistenceReturns() {
        var sequence = new ArrayList<String>();
        var processor = new ProcessTransaction(store(entry -> { throw new AssertionError("No ledger write"); }),
                CLOCK, new TransactionRules(), rejection -> sequence.add("persisted"), notification -> {
                    assertThat(sequence).containsExactly("persisted");
                    assertThat(notification).isEqualTo(new TransactionRejected(1, "TX-1", "CORR-1",
                            List.of("AMOUNT_NOT_POSITIVE"), NOW));
                    sequence.add("published");
                });
        assertThat(processor.process(event(1, BigDecimal.ZERO))).isInstanceOf(ProcessingResult.Rejected.class);
        assertThat(sequence).containsExactly("persisted", "published");
    }

    @Test
    void publicationFailurePropagatesAfterPersistenceAndReplayPublishesAgain() {
        var persisted = new ArrayList<TransactionRejection>();
        var published = new ArrayList<TransactionRejected>();
        var failure = new IllegalStateException("broker unavailable");
        var processor = new ProcessTransaction(store(entry -> { throw new AssertionError("No ledger write"); }),
                CLOCK, new TransactionRules(), persisted::add, notification -> {
                    published.add(notification);
                    if (published.size() == 1) throw failure;
                });
        var event = event(1, BigDecimal.ZERO);
        assertThatThrownBy(() -> processor.process(event)).isSameAs(failure);
        assertThat(persisted).hasSize(1);
        assertThat(processor.process(event)).isInstanceOf(ProcessingResult.Rejected.class);
        assertThat(published).hasSize(2);
    }
}
