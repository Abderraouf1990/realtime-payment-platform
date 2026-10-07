package com.aayadi.payment.processor.config;

import com.aayadi.payment.processor.adapter.kafka.KafkaListenerHealthIndicator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Set;

/** Explicit opt-in export of the small operational surface used by the local exercise. */
@Configuration(proxyBeanMethods = false)
@Profile("observability")
public class ObservabilityConfiguration {
    private static final Set<String> EXPORTED = Set.of(
            "payments.processing.attempts", "payments.consumer.lag",
            "payments.consumer.observation.available", "payments.consumer.observation.age",
            "payments.consumer.progress.age", "payments.consumer.listener.running");

    @Bean
    MeterRegistryCustomizer<PrometheusMeterRegistry> paymentExportOnly() {
        return registry -> registry.config().meterFilter(MeterFilter.deny(id -> !EXPORTED.contains(id.getName())));
    }

    @Bean
    MeterBinder listenerRunning(KafkaListenerHealthIndicator health) {
        return registry -> Gauge.builder("payments.consumer.listener.running", health,
                        h -> Status.UP.equals(h.health().getStatus()) ? 1 : 0)
                .description("1 only when the local listener health is UP; not a dependency probe")
                .register(registry);
    }
}
