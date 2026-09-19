package com.aayadi.payment.processor;

import com.aayadi.payment.contracts.v1.TransactionRejected;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionRejectedContractTests {
    @Test
    void roundTripsVersionOneWireFieldsWithoutJavaTypeHeaders() {
        var event = new TransactionRejected(1, "TX-1", "CORR-1",
                List.of("AMOUNT_NOT_POSITIVE", "CURRENCY_NOT_EUR"), Instant.parse("2026-09-19T10:00:00Z"));
        var headers = new RecordHeaders();
        try (var serializer = new JacksonJsonSerializer<TransactionRejected>();
             var deserializer = new JacksonJsonDeserializer<>(TransactionRejected.class, false)) {
            serializer.setAddTypeInfo(false);
            byte[] bytes = serializer.serialize("transactions.rejected", headers, event);
            assertThat(headers).isEmpty();
            var json = JsonMapper.builder().build().readTree(bytes);
            assertThat(json.size()).isEqualTo(5);
            assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(json.get("transactionId").asString()).isEqualTo("TX-1");
            assertThat(json.get("correlationId").asString()).isEqualTo("CORR-1");
            assertThat(json.get("reasonCodes").get(0).asString()).isEqualTo("AMOUNT_NOT_POSITIVE");
            assertThat(json.get("rejectedAt").asString()).isEqualTo("2026-09-19T10:00:00Z");
            headers.add("__TypeId__", "java.lang.Runtime".getBytes(StandardCharsets.UTF_8));
            assertThat(deserializer.deserialize("transactions.rejected", headers, bytes)).isEqualTo(event);
        }
    }
}
