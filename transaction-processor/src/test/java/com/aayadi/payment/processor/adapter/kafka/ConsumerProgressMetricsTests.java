package com.aayadi.payment.processor.adapter.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static com.aayadi.payment.processor.adapter.kafka.KafkaProgressSource.Offsets;

class ConsumerProgressMetricsTests {
    private final KafkaProgressSource source = mock(KafkaProgressSource.class);
    private final Clock clock = mock(Clock.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private ConsumerProgressMetrics metrics;
    private final Instant start = Instant.parse("2026-09-25T12:00:00Z");

    @BeforeEach void setUp() {
        when(clock.instant()).thenReturn(start);
        metrics = new ConsumerProgressMetrics(source, registry, clock);
    }

    @AfterEach void close() { registry.close(); }

    @Test void startsUnknownAndReadingGaugesDoesNotCallKafka() {
        assertThat(value("lag")).isEqualTo(-1);
        assertThat(value("observation.available")).isZero();
        assertThat(value("observation.age")).isEqualTo(-1);
        assertThat(value("progress.age")).isEqualTo(-1);
        assertThat(registry.getMeters()).hasSize(4).allSatisfy(m -> assertThat(m.getId().getTags()).isEmpty());
        verifyNoInteractions(source);
    }

    @Test void sumsAllPartitionsAndUsesEarliestWhenNoCommitExists() throws Exception {
        when(source.read()).thenReturn(Map.of(0, new Offsets(10, 16, null), 1, new Offsets(0, 9, 7L)));
        metrics.sample();
        assertThat(value("lag")).isEqualTo(8);
        assertThat(value("observation.available")).isEqualTo(1);
        assertThat(value("progress.age")).isEqualTo(-1);
    }

    @Test void idleIsZeroLagEvenWithNoProgressAndRecordsCommittedAdvance() throws Exception {
        when(source.read()).thenReturn(Map.of(0, new Offsets(0, 0, null)));
        metrics.sample();
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        metrics.sample();
        assertThat(value("lag")).isZero();
        assertThat(value("progress.age")).isEqualTo(-1);
        when(source.read()).thenReturn(Map.of(0, new Offsets(0, 3, 2L)));
        metrics.sample();
        assertThat(value("lag")).isEqualTo(1);
        assertThat(value("progress.age")).isZero();
        when(clock.instant()).thenReturn(start.plusSeconds(70));
        metrics.sample();
        assertThat(value("progress.age")).isEqualTo(10);
        assertThat(value("observation.age")).isZero();
    }

    @Test void brokerFailureInvalidatesLagRetainsAgeAndRecovers() throws Exception {
        when(source.read()).thenReturn(Map.of(0, new Offsets(0, 3, 1L)));
        metrics.sample();
        when(clock.instant()).thenReturn(start.plusSeconds(10));
        when(source.read()).thenThrow(new IllegalStateException("not logged"));
        metrics.sample();
        assertThat(value("observation.available")).isZero();
        assertThat(value("lag")).isEqualTo(-1);
        assertThat(value("observation.age")).isEqualTo(10);
        doReturn(Map.of(0, new Offsets(0, 3, 3L))).when(source).read();
        metrics.sample();
        assertThat(value("observation.available")).isEqualTo(1);
        assertThat(value("lag")).isZero();
        assertThat(value("progress.age")).isZero();
    }

    @Test void retentionAndInconsistentReadsAreUnknownRatherThanHealthyZero() throws Exception {
        for (var offsets : new Offsets[]{new Offsets(10, 20, 5L), new Offsets(0, 5, 6L), new Offsets(-1, 2, null)}) {
            when(source.read()).thenReturn(Map.of(0, offsets));
            metrics.sample();
            assertThat(value("lag")).isEqualTo(-1);
            assertThat(value("observation.available")).isZero();
        }
        when(source.read()).thenReturn(Map.of());
        metrics.sample();
        assertThat(value("observation.available")).isZero();
    }

    @Test void retentionWithoutCommitsDoesNotCountAsProcessingProgress() throws Exception {
        when(source.read()).thenReturn(Map.of(0, new Offsets(0, 8, null)));
        metrics.sample();
        when(source.read()).thenReturn(Map.of(0, new Offsets(4, 8, null)));
        metrics.sample();
        assertThat(value("lag")).isEqualTo(4);
        assertThat(value("progress.age")).isEqualTo(-1);
    }

    @Test void offsetResetAndNewProcessDoNotClaimHistoricalProgress() throws Exception {
        when(source.read()).thenReturn(Map.of(0, new Offsets(0, 8, 4L)));
        metrics.sample();
        when(source.read()).thenReturn(Map.of(0, new Offsets(0, 8, 8L)));
        metrics.sample();
        assertThat(value("progress.age")).isZero();
        when(source.read()).thenReturn(Map.of(0, new Offsets(0, 8, 2L)));
        metrics.sample();
        assertThat(value("progress.age")).isEqualTo(-1);
        assertThat(value("lag")).isEqualTo(6);
        registry.clear();
        metrics = new ConsumerProgressMetrics(source, registry, clock);
        metrics.sample();
        assertThat(value("progress.age")).isEqualTo(-1);
        assertThat(value("lag")).isEqualTo(6);
    }

    private double value(String suffix) {
        return registry.get("payments.consumer." + suffix).gauge().value();
    }
}
