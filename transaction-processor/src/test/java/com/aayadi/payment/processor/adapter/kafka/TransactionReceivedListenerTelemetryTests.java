package com.aayadi.payment.processor.adapter.kafka;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aayadi.payment.contracts.v1.TransactionReceived;
import com.aayadi.payment.contracts.v1.TransactionType;
import com.aayadi.payment.processor.application.LedgerStore;
import com.aayadi.payment.processor.application.ProcessTransaction;
import com.aayadi.payment.processor.application.ProcessingResult;
import com.aayadi.payment.processor.domain.TransactionRules.RejectionReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionReceivedListenerTelemetryTests {
    private final ProcessTransaction processor = mock(ProcessTransaction.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ProcessingTelemetry.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final TransactionReceivedListener listener = new TransactionReceivedListener(processor, new ProcessingTelemetry(registry));

    @BeforeEach void capture() { logs.start(); logger.addAppender(logs); }
    @AfterEach void cleanup() { logger.detachAppender(logs); logs.stop(); registry.close(); }

    @Test
    void acceptedThenDuplicateCountsAttemptsWithoutIdLabels() {
        var record = record("TX-1", "CORR-1");
        when(processor.process(record.value())).thenReturn(new ProcessingResult.Accepted(LedgerStore.Outcome.INSERTED),
                new ProcessingResult.Accepted(LedgerStore.Outcome.DUPLICATE));
        listener.receive(record);
        listener.receive(record);
        assertThat(count("accepted")).isEqualTo(1);
        assertThat(count("duplicate")).isEqualTo(1);
        assertThat(count("rejected")).isZero();
        assertThat(count("technical_failure")).isZero();
        assertThat(registry.getMeters()).hasSize(4).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).hasSize(1).allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("outcome")));
        assertThat(fields(1)).containsEntry("outcome", "duplicate").containsEntry("transactionId", "TX-1")
                .containsEntry("correlationId", "CORR-1").containsEntry("reasonCodes", List.of());
        verify(processor, times(2)).process(record.value());
    }

    @Test
    void combinedRejectionCountsOncePerAttemptAndKeepsReasonsInLogsOnly() {
        var record = record("TX-REJECT", "CORR-REJECT");
        when(processor.process(record.value())).thenReturn(new ProcessingResult.Rejected(
                List.of(RejectionReason.AMOUNT_NOT_POSITIVE, RejectionReason.CURRENCY_NOT_EUR)));
        listener.receive(record);
        listener.receive(record);
        assertThat(count("rejected")).isEqualTo(2);
        assertThat(count("technical_failure")).isZero();
        assertThat(fields(0)).containsEntry("reasonCodes", List.of("AMOUNT_NOT_POSITIVE", "CURRENCY_NOT_EUR"));
        assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain("PRIVATE-ACCOUNT", "99.99");
    }

    @Test
    void conflictRemainsRejection() {
        var record = record("TX-CONFLICT", "CORR-CONFLICT");
        when(processor.process(record.value())).thenReturn(new ProcessingResult.Rejected(List.of(RejectionReason.PAYLOAD_CONFLICT)));
        listener.receive(record);
        assertThat(count("rejected")).isEqualTo(1);
        assertThat(fields(0)).containsEntry("reasonCodes", List.of("PAYLOAD_CONFLICT"));
    }

    @Test
    void technicalFailurePropagatesSanitizedAndDoesNotCountAsSuccess() {
        var record = record("TX-FAILED", "CORR-FAILED");
        when(processor.process(record.value())).thenThrow(new IllegalStateException("jdbc:PRIVATE password=SECRET"));
        assertThatThrownBy(() -> listener.receive(record)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Ledger processing failed; offset must not be committed").hasNoCause();
        assertThat(count("technical_failure")).isEqualTo(1);
        assertThat(count("accepted")).isZero();
        assertThat(fields(0)).containsEntry("transactionId", "TX-FAILED").containsEntry("correlationId", "CORR-FAILED");
        assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain("PRIVATE", "SECRET");
        assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
    }

    @Test
    void invalidIdentifiersAndNullEnvelopeCannotLeakIntoLogs() {
        var invalid = record("SECRET\ninjected", "x".repeat(65));
        when(processor.process(invalid.value())).thenThrow(new IllegalArgumentException());
        when(processor.process(null)).thenThrow(new IllegalArgumentException());
        assertThatThrownBy(() -> listener.receive(invalid)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> listener.receive(new ConsumerRecord<>("transactions.received", 0, 8, "RAW-KEY", null)))
                .isInstanceOf(IllegalStateException.class);
        for (int i = 0; i < 2; i++) {
            assertThat(fields(i)).containsEntry("transactionId", "unavailable").containsEntry("correlationId", "unavailable");
            assertThat(logs.list.get(i).getFormattedMessage()).doesNotContain("SECRET", "RAW-KEY", "xxxxx");
        }
    }

    @Test
    void brokenMetricsCannotFailSuccessfulProcessingOrMaskProcessingFailure() {
        var broken = new SimpleMeterRegistry() {
            @Override protected Counter newCounter(Meter.Id id) {
                var counter = mock(Counter.class);
                when(counter.getId()).thenReturn(id);
                doThrow(new IllegalStateException("metric backend unavailable")).when(counter).increment();
                return counter;
            }
        };
        try {
            var observed = new TransactionReceivedListener(processor, new ProcessingTelemetry(broken));
            var record = record("TX-1", "CORR-1");
            when(processor.process(record.value())).thenReturn(new ProcessingResult.Accepted(LedgerStore.Outcome.INSERTED));
            assertThatCode(() -> observed.receive(record)).doesNotThrowAnyException();
            assertThat(fields(0)).containsEntry("outcome", "accepted");
            when(processor.process(record.value())).thenThrow(new IllegalStateException("private failure"));
            assertThatThrownBy(() -> observed.receive(record)).hasMessage("Ledger processing failed; offset must not be committed");
            assertThat(fields(1)).containsEntry("outcome", "technical_failure");
        } finally {
            broken.close();
        }
    }

    private double count(String outcome) {
        return registry.get(ProcessingTelemetry.METRIC).tag("outcome", outcome).counter().count();
    }

    private Map<String, Object> fields(int index) {
        return logs.list.get(index).getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private ConsumerRecord<String, TransactionReceived> record(String id, String correlation) {
        return new ConsumerRecord<>("transactions.received", 0, 7, id,
                new TransactionReceived(1, id, correlation, "PRIVATE-ACCOUNT", new BigDecimal("99.99"),
                        "EUR", TransactionType.TRANSFER, Instant.parse("2026-09-22T10:00:00Z")));
    }
}
