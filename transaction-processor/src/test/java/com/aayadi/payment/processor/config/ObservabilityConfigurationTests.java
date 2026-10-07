package com.aayadi.payment.processor.config;

import com.aayadi.payment.processor.adapter.kafka.KafkaListenerHealthIndicator;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ObservabilityConfigurationTests {
    @Test void exportUsesExactAllowlistAndPreservesBoundedOutcomeLabels() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            new ObservabilityConfiguration().paymentExportOnly().customize(registry);
            registry.counter("payments.processing.attempts", "outcome", "accepted").increment();
            registry.counter("payments.unapproved", "accountId", "SECRET").increment();
            registry.counter("jvm.unapproved").increment();
            assertThat(registry.scrape()).contains("payments_processing_attempts_total{outcome=\"accepted\"} 1.0")
                    .doesNotContain("unapproved", "SECRET", "accountId");
        } finally { registry.close(); }
    }

    @Test void listenerGaugeReadsExistingHealthWithoutChangingLifecycle() {
        var health = mock(KafkaListenerHealthIndicator.class);
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            new ObservabilityConfiguration().listenerRunning(health).bindTo(registry);
            when(health.health()).thenReturn(Health.up().build(), Health.down().build(), Health.outOfService().build());
            var gauge = registry.get("payments.consumer.listener.running").gauge();
            assertThat(gauge.value()).isEqualTo(1);
            assertThat(gauge.value()).isZero();
            assertThat(gauge.value()).isZero();
            assertThat(gauge.getId().getTags()).isEmpty();
            verify(health, times(3)).health();
            verifyNoMoreInteractions(health);
        } finally { registry.close(); }
    }
}
