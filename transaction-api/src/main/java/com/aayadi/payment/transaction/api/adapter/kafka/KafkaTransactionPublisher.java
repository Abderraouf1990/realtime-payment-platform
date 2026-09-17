package com.aayadi.payment.transaction.api.adapter.kafka;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import com.aayadi.payment.transaction.api.application.PublicationException;
import com.aayadi.payment.transaction.api.application.TransactionPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class KafkaTransactionPublisher implements TransactionPublisher {
    private final KafkaTemplate<String, TransactionReceived> kafka;
    private final String topic;
    private final Duration timeout;

    public KafkaTransactionPublisher(KafkaTemplate<String, TransactionReceived> kafka,
            @Value("${payments.kafka.received-topic}") String topic,
            @Value("${payments.kafka.publish-timeout:10s}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.toMillis() == 0) {
            throw new IllegalArgumentException("Publication timeout must be at least one millisecond");
        }
        this.kafka = kafka;
        this.topic = topic;
        this.timeout = timeout;
    }

    @Override
    public void publish(TransactionReceived event) {
        try {
            kafka.send(topic, event.transactionId(), event).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PublicationException(exception);
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            // A timeout is an uncertain outcome: the broker may still have accepted the event.
            throw new PublicationException(exception);
        }
    }
}
