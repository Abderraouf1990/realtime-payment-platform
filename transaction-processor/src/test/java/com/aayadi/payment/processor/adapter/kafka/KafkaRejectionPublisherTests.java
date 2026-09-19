package com.aayadi.payment.processor.adapter.kafka;

import com.aayadi.payment.contracts.v1.TransactionRejected;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class KafkaRejectionPublisherTests {
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, TransactionRejected> kafka = mock(KafkaTemplate.class);
    private final TransactionRejected event = new TransactionRejected(1, "TX-1", "CORR-1",
            List.of("PAYLOAD_CONFLICT"), Instant.parse("2026-09-19T10:00:00Z"));

    @Test
    @SuppressWarnings("unchecked")
    void usesConfiguredTopicAndTransactionKeyAndWaitsForConfirmation() throws Exception {
        CompletableFuture<SendResult<String, TransactionRejected>> future = mock(CompletableFuture.class);
        when(kafka.send("custom.rejections", "TX-1", event)).thenReturn(future);
        new KafkaRejectionPublisher(kafka, "custom.rejections", Duration.ofSeconds(2)).publish(event);
        verify(future).get(2000, TimeUnit.MILLISECONDS);
    }

    @Test
    void brokerFailureAndTimeoutAreTechnicalFailures() {
        when(kafka.send("rejected", "TX-1", event))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("private broker details")))
                .thenReturn(new CompletableFuture<>());
        var publisher = new KafkaRejectionPublisher(kafka, "rejected", Duration.ofMillis(10));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Rejection publication failed or timed out").hasNoCause();
        }
    }

    @Test
    void preservesInterruptStatus() {
        when(kafka.send("rejected", "TX-1", event)).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new KafkaRejectionPublisher(kafka, "rejected", Duration.ofSeconds(1)).publish(event))
                    .hasMessage("Rejection publication interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
