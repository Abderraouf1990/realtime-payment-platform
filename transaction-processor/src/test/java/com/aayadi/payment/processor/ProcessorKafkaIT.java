package com.aayadi.payment.processor;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import com.aayadi.payment.contracts.v1.TransactionType;
import com.aayadi.payment.processor.application.LedgerStore;
import com.aayadi.payment.processor.domain.LedgerEntry;
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
import org.springframework.test.annotation.DirtiesContext;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProcessorKafkaIT {
    private static final String TOPIC = "transactions.received";
    private static final String GROUP = "processor-integration";
    @Autowired private KafkaContainer kafka;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private LedgerStore store;

    @Test
    void postgresComparesAfterInsertConflictWithoutChangingExistingRow() {
        var now = Instant.parse("2026-09-18T10:00:00Z");
        var original = new LedgerEntry("TX-STORE", "CORR-FIRST", "ACC-1", new BigDecimal("10.00"),
                "EUR", TransactionType.TRANSFER, now, now);
        assertThat(store.save(original)).isEqualTo(LedgerStore.Outcome.INSERTED);
        var row = jdbc.queryForMap("SELECT * FROM ledger_transactions WHERE transaction_id = 'TX-STORE'");
        assertThat(store.save(original)).isEqualTo(LedgerStore.Outcome.DUPLICATE);
        assertThat(store.save(new LedgerEntry("TX-STORE", "CORR-NEW", "ACC-1", new BigDecimal("10.0"),
                "EUR", TransactionType.TRANSFER, now.plusSeconds(1), now.plusSeconds(2))))
                .isEqualTo(LedgerStore.Outcome.DUPLICATE);
        for (var changed : new LedgerEntry[] {
                new LedgerEntry("TX-STORE", "CORR-NEW", "ACC-2", original.amount(), "EUR", original.type(), now, now),
                new LedgerEntry("TX-STORE", "CORR-NEW", "ACC-1", new BigDecimal("11.00"), "EUR", original.type(), now, now),
                new LedgerEntry("TX-STORE", "CORR-NEW", "ACC-1", original.amount(), "USD", original.type(), now, now)}) {
            assertThat(store.save(changed)).isEqualTo(LedgerStore.Outcome.CONFLICT);
            assertThat(jdbc.queryForMap("SELECT * FROM ledger_transactions WHERE transaction_id = 'TX-STORE'")).isEqualTo(row);
        }
        // Test-only historical value: do not extend the shared wire enum to simulate another type.
        jdbc.update("UPDATE ledger_transactions SET type = 'OTHER' WHERE transaction_id = 'TX-STORE'");
        var historical = jdbc.queryForMap("SELECT * FROM ledger_transactions WHERE transaction_id = 'TX-STORE'");
        assertThat(store.save(original)).isEqualTo(LedgerStore.Outcome.CONFLICT);
        assertThat(jdbc.queryForMap("SELECT * FROM ledger_transactions WHERE transaction_id = 'TX-STORE'")).isEqualTo(historical);
        assertThat(count("TX-STORE")).isEqualTo(1);
    }

    @Test
    void publishingExactlyTheSameEventTwiceCreatesOneLedgerRow(CapturedOutput output) throws Exception {
        try (var producer = new KafkaProducer<String, String>(Map.of("bootstrap.servers", kafka.getBootstrapServers()),
                new StringSerializer(), new StringSerializer());
             var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            var event = event("TX-IDENTICAL", "CORR-IDENTICAL", "42.00");
            long first = send(producer, event);
            long second = send(producer, event);
            assertThat(second).isEqualTo(first + 1);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(committed(admin)).isEqualTo(second + 1));
            assertThat(count(event.transactionId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Integer.class)).isEqualTo(1);
            assertThat(registry.getListenerContainer("transaction-received").isRunning()).isTrue();
            assertThat(output.getOut()).contains("transactionId=TX-IDENTICAL outcome=INSERTED",
                    "transactionId=TX-IDENTICAL outcome=DUPLICATE");
            assertThat(output.getAll()).doesNotContain("Ledger processing failed", "PRIVATE-ACCOUNT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_rejections", Integer.class)).isZero();
        }
    }

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

            var rejected = new TransactionReceived(1, "TX-REJECTED", "CORR-REJECTED", "PRIVATE-ACCOUNT",
                    BigDecimal.ZERO, "USD", null, event.receivedAt());
            long rejectedOffset = send(producer, rejected);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(committed(admin)).isEqualTo(rejectedOffset + 1));
            assertThat(count("TX-REJECTED")).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_rejections WHERE transaction_id='TX-REJECTED'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT reason_codes::text FROM transaction_rejections WHERE transaction_id='TX-REJECTED'", String.class))
                    .isEqualTo("{AMOUNT_NOT_POSITIVE,CURRENCY_NOT_EUR,TYPE_NOT_TRANSFER}");
            assertThat(listener.isRunning()).isTrue();
            assertThat(output.getOut()).contains("Transaction rejected correlationId=CORR-REJECTED transactionId=TX-REJECTED",
                    "reasons=[AMOUNT_NOT_POSITIVE, CURRENCY_NOT_EUR, TYPE_NOT_TRANSFER]");

            // Deferred constraint trigger: INSERT succeeds; the database rejects COMMIT itself.
            jdbc.execute("""
                    CREATE FUNCTION test_fail_ledger_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.transaction_id = 'TX-FAIL' THEN
                            RAISE EXCEPTION 'Injected ledger commit failure' USING ERRCODE = '23514';
                        END IF;
                        RETURN NEW;
                    END;
                    $$
                    """);
            jdbc.execute("""
                    CREATE CONSTRAINT TRIGGER test_storage_failure
                    AFTER INSERT ON ledger_transactions DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW EXECUTE FUNCTION test_fail_ledger_commit()
                    """);
            long failed = send(producer, event("TX-FAIL", "CORR-FAIL", "10.00"));
            await().atMost(Duration.ofSeconds(15)).until(() -> !listener.isRunning());
            assertThat(committed(admin)).isEqualTo(failed);
            assertThat(count("TX-FAIL")).isZero();

            jdbc.execute("DROP TRIGGER test_storage_failure ON ledger_transactions");
            jdbc.execute("DROP FUNCTION test_fail_ledger_commit()");
            listener.start();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(count("TX-FAIL")).isEqualTo(1);
                assertThat(committed(admin)).isEqualTo(failed + 1);
            });

            long conflict = send(producer, event("TX-INTEGRATION", "CORR-CONFLICT", "999.00"));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(committed(admin)).isEqualTo(conflict + 1));
            assertThat(listener.isRunning()).isTrue();
            assertThat(jdbc.queryForMap("SELECT * FROM ledger_transactions WHERE transaction_id = ?", event.transactionId())).isEqualTo(row);
            var changedCurrency = new TransactionReceived(1, event.transactionId(), "CORR-USD", event.accountId(),
                    event.amount(), "USD", event.type(), event.receivedAt());
            long currencyConflict = send(producer, changedCurrency);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(committed(admin)).isEqualTo(currencyConflict + 1));
            assertThat(count(event.transactionId())).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT * FROM ledger_transactions WHERE transaction_id = ?", event.transactionId())).isEqualTo(row);
            assertThat(output.getOut()).contains("transactionId=TX-INTEGRATION correlationId=CORR-CONFLICT reason=PAYLOAD_CONFLICT",
                    "transactionId=TX-INTEGRATION correlationId=CORR-USD reason=PAYLOAD_CONFLICT");
            assertThat(output.getOut()).contains("correlationId=CORR-INTEGRATION", "correlationId=CORR-RETRY", "correlationId=CORR-FAIL");
            assertThat(output.getAll()).doesNotContain("PRIVATE-ACCOUNT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_rejections WHERE transaction_id='TX-INTEGRATION'", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT reason_codes::text FROM transaction_rejections WHERE transaction_id='TX-INTEGRATION'", String.class))
                    .containsOnly("{PAYLOAD_CONFLICT}");
        }
    }

    @Test
    void rejectionCommitFailurePreventsAckThenReplayAndRetriesCreateOneAuditRow() throws Exception {
        var listener = registry.getListenerContainer("transaction-received");
        try (var producer = new KafkaProducer<String, String>(Map.of("bootstrap.servers", kafka.getBootstrapServers()),
                new StringSerializer(), new StringSerializer());
             var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            long baseline = send(producer, event("TX-BASELINE", "CORR-BASELINE", "1.00"));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(committed(admin)).isEqualTo(baseline + 1));
            jdbc.execute("""
                    CREATE FUNCTION test_fail_rejection_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'Injected rejection commit failure' USING ERRCODE = '23514';
                    END;
                    $$
                    """);
            jdbc.execute("""
                    CREATE CONSTRAINT TRIGGER test_rejection_failure
                    AFTER INSERT ON transaction_rejections DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW EXECUTE FUNCTION test_fail_rejection_commit()
                    """);
            var rejected = event("TX-REJECT-FAIL", "CORR-REJECT-FIRST", "-1.00");
            long failed = send(producer, rejected);
            await().atMost(Duration.ofSeconds(20)).until(() -> !listener.isRunning());
            assertThat(committed(admin)).isEqualTo(failed);
            assertThat(count(rejected.transactionId())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_rejections", Integer.class)).isZero();

            jdbc.execute("DROP TRIGGER test_rejection_failure ON transaction_rejections");
            jdbc.execute("DROP FUNCTION test_fail_rejection_commit()");
            listener.start();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(committed(admin)).isEqualTo(failed + 1));
            String original = jdbc.queryForObject("SELECT row_to_json(r)::text FROM transaction_rejections r", String.class);
            long repeated = send(producer, rejected);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(committed(admin)).isEqualTo(repeated + 1));
            long metadataRetry = send(producer, event("TX-REJECT-FAIL", "CORR-REJECT-RETRY", "-1.0"));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(committed(admin)).isEqualTo(metadataRetry + 1));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_rejections", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT row_to_json(r)::text FROM transaction_rejections r", String.class)).isEqualTo(original);
            assertThat(jdbc.queryForObject("SELECT reason_codes::text FROM transaction_rejections", String.class)).isEqualTo("{AMOUNT_NOT_POSITIVE}");
            assertThat(count(rejected.transactionId())).isZero();
            assertThat(listener.isRunning()).isTrue();
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
