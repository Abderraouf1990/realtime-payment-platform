package com.aayadi.payment.processor.adapter.kafka;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/** Local listener lifecycle only; UP does not prove broker reachability or processing progress. */
@Component("kafkaListener")
public class KafkaListenerHealthIndicator implements HealthIndicator {
    private final KafkaListenerEndpointRegistry registry;
    private volatile boolean applicationReady;

    public KafkaListenerHealthIndicator(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    void applicationReady() {
        applicationReady = true;
    }

    @Override
    public Health health() {
        if (!applicationReady) {
            return Health.outOfService().withDetail("state", "STARTING").build();
        }
        var container = registry.getListenerContainer(TransactionReceivedListener.LISTENER_ID);
        if (container == null) {
            return Health.down().withDetail("state", "MISSING").build();
        }
        if (!container.isRunning() || !container.isInExpectedState()) {
            return Health.down().withDetail("state", "STOPPED").build();
        }
        if (container.isPauseRequested()) {
            return Health.outOfService().withDetail("state", "PAUSED").build();
        }
        return Health.up().withDetail("state", "RUNNING").build();
    }
}
