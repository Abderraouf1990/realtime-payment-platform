package com.aayadi.payment.processor.adapter.kafka;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Cached, bounded group metrics; HTTP reads never contact Kafka. */
@Component
public class ConsumerProgressMetrics {
    private final KafkaProgressSource source;
    private final Clock clock;
    private Map<Integer, KafkaProgressSource.Offsets> previous = Map.of();
    private volatile Snapshot snapshot = new Snapshot(false, -1, null, null);

    public ConsumerProgressMetrics(KafkaProgressSource source, MeterRegistry registry, Clock clock) {
        this.source = source;
        this.clock = clock;
        Gauge.builder("payments.consumer.lag", this, m -> m.snapshot.lag())
                .description("Sum of end minus committed offsets; -1 when unavailable").register(registry);
        Gauge.builder("payments.consumer.observation.available", this, m -> m.snapshot.available() ? 1 : 0)
                .description("Whether the last offset observation succeeded").register(registry);
        Gauge.builder("payments.consumer.observation.age", this, m -> m.age(m.snapshot.observedAt()))
                .baseUnit("seconds").description("Age of last successful observation; -1 before first success")
                .register(registry);
        Gauge.builder("payments.consumer.progress.age", this, m -> m.age(m.snapshot.progressAt()))
                .baseUnit("seconds").description("Age of last observed committed-offset advance; -1 if unknown")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${payments.monitoring.interval:5000}",
            initialDelayString = "${payments.monitoring.interval:5000}")
    public void sample() {
        try {
            var offsets = source.read();
            if (offsets.isEmpty()) throw new IllegalStateException("No partitions");
            long lag = 0;
            boolean advanced = false;
            boolean reset = !offsets.keySet().equals(previous.keySet());
            for (var entry : offsets.entrySet()) {
                var current = entry.getValue();
                // Retention/truncation or inconsistent concurrent reads must not imply zero backlog.
                if (current.earliest() < 0 || current.position() < current.earliest()
                        || current.end() < current.position()) throw new IllegalStateException("Invalid offsets");
                lag = Math.addExact(lag, current.end() - current.position());
                var before = previous.get(entry.getKey());
                if (before != null) {
                    reset |= current.position() < before.position()
                            || (before.committed() != null && current.committed() == null);
                    advanced |= current.committed() != null && current.committed() > before.position();
                }
            }
            Instant now = clock.instant();
            Instant progress = reset ? null : advanced ? now : snapshot.progressAt();
            snapshot = new Snapshot(true, lag, now, progress);
            previous = Map.copyOf(offsets);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            unavailable();
        } catch (Exception failure) {
            // Monitoring failure cannot stop, restart, acknowledge or log payment records.
            unavailable();
        }
    }

    private void unavailable() {
        var last = snapshot;
        snapshot = new Snapshot(false, -1, last.observedAt(), last.progressAt());
    }

    private double age(Instant time) {
        return time == null ? -1 : Math.max(0, Duration.between(time, clock.instant()).toMillis() / 1000.0);
    }

    private record Snapshot(boolean available, long lag, Instant observedAt, Instant progressAt) { }
}
