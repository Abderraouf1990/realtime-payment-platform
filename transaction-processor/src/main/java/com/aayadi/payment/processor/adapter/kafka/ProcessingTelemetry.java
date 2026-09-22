package com.aayadi.payment.processor.adapter.kafka;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Best-effort attempt telemetry, never an acknowledgement or an audit record. */
@Component
public class ProcessingTelemetry {
    public static final String METRIC = "payments.processing.attempts";
    public enum Outcome { ACCEPTED, DUPLICATE, REJECTED, TECHNICAL_FAILURE }

    private static final Logger LOG = LoggerFactory.getLogger(ProcessingTelemetry.class);
    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    public ProcessingTelemetry(MeterRegistry registry) {
        for (var outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder(METRIC)
                    .description("Completed listener attempts, before Kafka acknowledgement; resets on restart")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT)).register(registry));
        }
    }

    public void record(Outcome outcome, ConsumerRecord<String, TransactionReceived> record,
                       List<String> reasons, String message) {
        try {
            counters.get(outcome).increment();
        } catch (RuntimeException ignored) {
            // A metrics backend failure must not turn committed work into a failed delivery.
        }
        try {
            var event = record.value();
            var log = outcome == Outcome.TECHNICAL_FAILURE ? LOG.atWarn() : LOG.atInfo();
            log.addKeyValue("event", "payment.processing")
                    .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .addKeyValue("transactionId", safeId(event == null ? null : event.transactionId()))
                    .addKeyValue("correlationId", safeId(event == null ? null : event.correlationId()))
                    .addKeyValue("reasonCodes", reasons)
                    .addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset())
                    .log(message);
        } catch (RuntimeException ignored) {
            // Logging is also best effort, independent of processing/acknowledgement.
        }
    }

    static String safeId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}") ? value : "unavailable";
    }
}
