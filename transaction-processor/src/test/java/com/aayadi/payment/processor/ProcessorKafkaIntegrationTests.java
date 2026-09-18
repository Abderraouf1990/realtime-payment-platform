package com.aayadi.payment.processor;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import com.aayadi.payment.contracts.v1.TransactionType;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = "spring.kafka.consumer.group-id=processor-integration")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ProcessorKafkaIntegrationTests {
    private static final String TOPIC = "transactions.received";
    private static final String GROUP = "processor-integration";
    @Autowired private KafkaContainer kafka;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KafkaListenerEndpointRegistry registry;

    @Test
    void persistsBeforeCommitDeduplicatesAndReplaysAfterDatabaseFailure(CapturedOutput output) throws Exception {
        var listener = registry.getListenerContainer("transaction-received");
        assertThat(listener).isNotNull();
        try (var producer = new KafkaProducer<String, String>(Map.of("bootstrap.servers", kafka.getBootstrapServers()),
                new StringSerializer(), new StringSerializer());
             var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            var event = event("TX-INTEGRATION", "CORR-INTEGRATION", "250.00");
            long first = send(producer, event);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(count(event.transactionId())).isEqualTo(1);
                assertThat(committed(admin)).isEqualTo(first + 1);
            });
            var row = jdbc.queryForMap("SELECT * FROM ledger_transactions WHERE transaction_id = ?", event.transactionId());
            assertThat(row.get("correlation_id")).isEqualTo(event.correlationId());
            assertThat(row.get("account_id")).isEqualTo(event.accountId());
            assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(event.amount());
            assertThat(row.get("currency")).isEqualTo("EUR");
            assertThat(row.get("type")).isEqualTo("TRANSFER");
            assertThat(jdbc.queryForObject("SELECT received_at FROM ledger_transactions WHERE transaction_id = ?",
                    OffsetDateTime.class, event.transactionId()).toInstant()).isEqualTo(event.receivedAt());
            assertThat(row.get("processed_at")).isNotNull();

            long duplicate = send(producer, event("TX-INTEGRATION", "CORR-RETRY", "250.00"));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(committed(admin)).isEqualTo(duplicate + 1));
            assertThat(count(event.transactionId())).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT * FROM ledger_transactions WHERE transaction_id = ?", event.transactionId())).isEqualTo(row);

            jdbc.execute("ALTER TABLE ledger_transactions ADD CONSTRAINT test_storage_failure CHECK (transaction_id <> 'TX-FAIL')");
            long failed = send(producer, event("TX-FAIL", "CORR-FAIL", "10.00"));
            await().atMost(Duration.ofSeconds(15)).until(() -> !listener.isRunning());
            assertThat(committed(admin)).isEqualTo(failed);
            assertThat(count("TX-FAIL")).isZero();

            jdbc.execute("ALTER TABLE ledger_transactions DROP CONSTRAINT test_storage_failure");
            listener.start();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(count("TX-FAIL")).isEqualTo(1);
                assertThat(committed(admin)).isEqualTo(failed + 1);
            });

            long conflict = send(producer, event("TX-INTEGRATION", "CORR-CONFLICT", "999.00"));
            await().atMost(Duration.ofSeconds(15)).until(() -> !listener.isRunning());
            assertThat(committed(admin)).isEqualTo(conflict);
            assertThat(jdbc.queryForMap("SELECT * FROM ledger_transactions WHERE transaction_id = ?", event.transactionId())).isEqualTo(row);
            assertThat(output.getOut()).contains("correlationId=CORR-INTEGRATION", "correlationId=CORR-RETRY", "correlationId=CORR-FAIL");
            assertThat(output.getAll()).doesNotContain("PRIVATE-ACCOUNT");
        }
    }

    private int count(String transactionId) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE transaction_id = ?", Integer.class, transactionId);
    }

    private static long committed(Admin admin) throws Exception {
        var offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
                .get(new TopicPartition(TOPIC, 0));
        return offset == null ? -1 : offset.offset();
    }

    private static long send(KafkaProducer<String, String> producer, TransactionReceived event) throws Exception {
        var record = new ProducerRecord<>(TOPIC, event.transactionId(), JsonMapper.builder().build().writeValueAsString(event));
        // An untrusted Java type header must never override the explicitly configured shared contract.
        record.headers().add("__TypeId__", "java.lang.Runtime".getBytes(StandardCharsets.UTF_8));
        return producer.send(record).get(10, TimeUnit.SECONDS).offset();
    }

    private static TransactionReceived event(String id, String correlation, String amount) {
        return new TransactionReceived(1, id, correlation, "PRIVATE-ACCOUNT", new BigDecimal(amount),
                "EUR", TransactionType.TRANSFER, Instant.parse("2026-09-18T10:00:00.123456Z"));
    }
}
