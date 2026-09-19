package com.aayadi.payment.processor.adapter.kafka;

import com.aayadi.payment.contracts.v1.TransactionRejected;
import com.aayadi.payment.processor.application.RejectionPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaRejectionPublisher implements RejectionPublisher {
    private final KafkaTemplate<String, TransactionRejected> kafka;
    private final String topic;
    private final Duration timeout;

    public KafkaRejectionPublisher(KafkaTemplate<String, TransactionRejected> kafka,
            @Value("${payments.kafka.rejected-topic}") String topic,
            @Value("${payments.kafka.publish-timeout:10s}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.toMillis() == 0) {
            throw new IllegalArgumentException("Publication timeout must be at least one millisecond");
        }
        this.kafka = kafka;
        this.topic = topic;
        this.timeout = timeout;
    }

    @Override
    public void publish(TransactionRejected event) {
        try {
            kafka.send(topic, event.transactionId(), event).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Rejection publication interrupted");
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            // Timeout is uncertain: publication may have succeeded. Do not acknowledge input.
            throw new IllegalStateException("Rejection publication failed or timed out");
        }
    }
}
