package com.aayadi.payment.transaction.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import tools.jackson.databind.json.JsonMapper;
import com.aayadi.payment.contracts.v1.TransactionReceived;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TransactionPublicationIT {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private KafkaContainer kafka;

    @Test
    void publishesVersionedJsonAndPreservesIdentityOnRetry() throws Exception {
        String id = "TX-" + UUID.randomUUID();
        String correlationId = "CORR-" + UUID.randomUUID();
        String body = """
                {"transactionId":"%s","correlationId":"%s","accountId":"ACC-1","amount":250.00,"currency":"EUR","type":"TRANSFER"}
                """.formatted(id, correlationId);
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (var consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("transactions.received"));
            Instant before = Instant.now();
            Integer partition = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", id)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isAccepted()).andExpect(jsonPath("$.transactionId").value(id))
                        .andExpect(jsonPath("$.correlationId").value(correlationId));
                var record = KafkaTestUtils.getSingleRecord(consumer, "transactions.received", Duration.ofSeconds(20));
                assertThat(record.key()).isEqualTo(id);
                assertThat(record.headers().lastHeader("__TypeId__")).isNull();
                var mapper = JsonMapper.builder().build();
                var json = mapper.readTree(record.value());
                assertThat(json.size()).isEqualTo(8);
                assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
                assertThat(json.get("transactionId").asString()).isEqualTo(id);
                assertThat(json.get("correlationId").asString()).isEqualTo(correlationId);
                assertThat(json.get("accountId").asString()).isEqualTo("ACC-1");
                assertThat(json.get("amount").decimalValue()).isEqualByComparingTo("250.00");
                assertThat(json.get("currency").asString()).isEqualTo("EUR");
                assertThat(json.get("type").asString()).isEqualTo("TRANSFER");
                TransactionReceived event = mapper.readValue(record.value(), TransactionReceived.class);
                assertThat(event.correlationId()).isEqualTo(correlationId);
                assertThat(event.receivedAt()).isBetween(before, Instant.now());
                if (partition != null) {
                    assertThat(record.partition()).isEqualTo(partition);
                }
                partition = record.partition();
            }
            assertThat(consumer.poll(Duration.ofMillis(500)).isEmpty()).isTrue();
        }
    }
}
