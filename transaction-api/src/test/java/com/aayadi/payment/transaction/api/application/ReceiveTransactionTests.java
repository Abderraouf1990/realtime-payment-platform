package com.aayadi.payment.transaction.api.application;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import com.aayadi.payment.contracts.v1.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiveTransactionTests {
    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void mapsAllFieldsAndPublishesOneVersionedEvent() {
        List<TransactionReceived> events = new ArrayList<>();
        ReceiveTransaction intake = new ReceiveTransaction(events::add, CLOCK);
        String id = intake.receive("TX-1", "CORR-1", "ACC-1", new BigDecimal("250.00"), "EUR", TransactionType.TRANSFER);
        assertThat(id).isEqualTo("TX-1");
        assertThat(events).containsExactly(new TransactionReceived(1, "TX-1", "CORR-1", "ACC-1",
                new BigDecimal("250.00"), "EUR", TransactionType.TRANSFER, NOW));
    }

    @Test
    void retryPreservesBusinessIdentityAndMayPublishAnotherEvent() {
        List<TransactionReceived> events = new ArrayList<>();
        ReceiveTransaction intake = new ReceiveTransaction(events::add, CLOCK);
        for (int i = 0; i < 2; i++) {
            assertThat(intake.receive("TX-1", "CORR-1", "ACC-1", BigDecimal.TEN, "EUR", TransactionType.TRANSFER))
                    .isEqualTo("TX-1");
        }
        assertThat(events).hasSize(2).extracting(TransactionReceived::transactionId).containsOnly("TX-1");
        assertThat(events).extracting(TransactionReceived::correlationId).containsOnly("CORR-1");
    }

    @Test
    void propagatesUnconfirmedPublication() {
        PublicationException failure = new PublicationException(new IllegalStateException("offline"));
        ReceiveTransaction intake = new ReceiveTransaction(event -> { throw failure; }, CLOCK);
        assertThatThrownBy(() -> intake.receive("TX-1", "CORR-1", "ACC-1", BigDecimal.TEN, "EUR", TransactionType.TRANSFER))
                .isSameAs(failure);
    }
}
