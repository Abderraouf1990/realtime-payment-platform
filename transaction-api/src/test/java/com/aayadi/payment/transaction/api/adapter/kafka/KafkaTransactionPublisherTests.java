package com.aayadi.payment.transaction.api.adapter.kafka;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import com.aayadi.payment.contracts.v1.TransactionType;
import com.aayadi.payment.transaction.api.application.PublicationException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaTransactionPublisherTests {
    private static final String TOPIC = "transactions.received";
    private static final TransactionReceived EVENT = new TransactionReceived(1, "TX-1", "CORR-1", "ACC-1",
            BigDecimal.TEN, "EUR", TransactionType.TRANSFER, Instant.parse("2026-09-17T10:00:00Z"));
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, TransactionReceived> kafka = mock(KafkaTemplate.class);

    @Test
    void waitsForBrokerConfirmationAndUsesTransactionIdAsKey() throws Exception {
        CompletableFuture<SendResult<String, TransactionReceived>> confirmation = new CompletableFuture<>();
        CountDownLatch sent = new CountDownLatch(1);
        when(kafka.send(TOPIC, "TX-1", EVENT)).thenAnswer(invocation -> {
            sent.countDown();
            return confirmation;
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var publication = executor.submit(() -> publisher(Duration.ofSeconds(5)).publish(EVENT));
            try {
                assertThat(sent.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> publication.get(50, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                confirmation.complete(null);
            }
            publication.get(2, TimeUnit.SECONDS);
        }
        verify(kafka).send(TOPIC, "TX-1", EVENT);
    }

    @Test
    void wrapsAsynchronousFailure() {
        when(kafka.send(TOPIC, "TX-1", EVENT))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        assertThatThrownBy(() -> publisher(Duration.ofSeconds(1)).publish(EVENT))
                .isInstanceOf(PublicationException.class).hasRootCauseMessage("broker unavailable");
    }

    @Test
    void wrapsSynchronousFailure() {
        when(kafka.send(TOPIC, "TX-1", EVENT)).thenThrow(new IllegalStateException("buffer unavailable"));
        assertThatThrownBy(() -> publisher(Duration.ofSeconds(1)).publish(EVENT))
                .isInstanceOf(PublicationException.class).hasRootCauseMessage("buffer unavailable");
    }

    @Test
    void timeoutDoesNotClaimSuccessOrCancelAnUncertainSend() {
        CompletableFuture<SendResult<String, TransactionReceived>> confirmation = new CompletableFuture<>();
        when(kafka.send(TOPIC, "TX-1", EVENT)).thenReturn(confirmation);
        assertThatThrownBy(() -> publisher(Duration.ofMillis(20)).publish(EVENT))
                .isInstanceOf(PublicationException.class).hasCauseInstanceOf(TimeoutException.class);
        assertThat(confirmation.isCancelled()).isFalse();
    }

    @Test
    void interruptionPreservesInterruptFlag() {
        when(kafka.send(TOPIC, "TX-1", EVENT)).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> publisher(Duration.ofSeconds(1)).publish(EVENT))
                    .isInstanceOf(PublicationException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private KafkaTransactionPublisher publisher(Duration timeout) {
        return new KafkaTransactionPublisher(kafka, TOPIC, timeout);
    }
}
