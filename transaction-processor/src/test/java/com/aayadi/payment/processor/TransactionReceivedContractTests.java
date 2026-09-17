package com.aayadi.payment.processor;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionReceivedContractTests {
    @Test
    void deserializesCorrelationIdFromVersionOneEventWithoutTypeHeaders() {
        String json = """
                {"schemaVersion":1,"transactionId":"TX-1","correlationId":"CORR-1",
                 "accountId":"ACC-1","amount":250.00,"currency":"EUR","type":"TRANSFER",
                 "receivedAt":"2026-09-17T10:00:00Z"}
                """;
        try (var deserializer = new JacksonJsonDeserializer<>(TransactionReceived.class)) {
            TransactionReceived event = deserializer.deserialize("transactions.received", json.getBytes(StandardCharsets.UTF_8));
            assertThat(event.schemaVersion()).isEqualTo(1);
            assertThat(event.transactionId()).isEqualTo("TX-1");
            assertThat(event.correlationId()).isEqualTo("CORR-1");
        }
    }
}
