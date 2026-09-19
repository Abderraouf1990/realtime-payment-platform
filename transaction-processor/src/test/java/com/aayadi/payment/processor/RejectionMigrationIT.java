package com.aayadi.payment.processor;

import com.aayadi.payment.contracts.v1.TransactionType;
import com.aayadi.payment.processor.adapter.postgres.JdbcRejectionStore;
import com.aayadi.payment.processor.domain.TransactionRejection;
import com.aayadi.payment.processor.domain.TransactionRules.RejectionReason;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RejectionMigrationIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6");
    private static JdbcTemplate jdbc;
    private static JdbcRejectionStore store;
    private static TransactionTemplate transaction;
    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00.123456Z");

    @BeforeAll
    static void upgradeFromV1() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        var v1 = Flyway.configure().dataSource(source).target("1").load();
        assertThat(v1.migrate().migrationsExecuted).isEqualTo(1);
        jdbc.update("""
                INSERT INTO ledger_transactions (transaction_id, correlation_id, account_id, amount, currency,
                    type, received_at, processed_at) VALUES ('TX-BEFORE-V2', 'CORR-1', 'ACC-1', 10, 'EUR', 'TRANSFER', now(), now())
                """);
        var latest = Flyway.configure().dataSource(source).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("2");
        latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        store = new JdbcRejectionStore(jdbc);
        transaction = new TransactionTemplate(new JdbcTransactionManager(source));
    }

    @Test
    void migrationPreservesLedgerAndProvidesAuditIndexes() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions", Integer.class)).isEqualTo(1);
        var indexes = jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND tablename = 'transaction_rejections'", String.class);
        assertThat(indexes).anyMatch(index -> index.contains("(transaction_id, account_id, amount, currency, type) NULLS NOT DISTINCT"));
        assertThat(indexes).anyMatch(index -> index.contains("(rejected_at)"));
    }

    @Test
    void preservesAuditValuesAndDeduplicatesBusinessEventRegardlessOfMetadata() {
        var reasons = List.of(RejectionReason.AMOUNT_NOT_POSITIVE, RejectionReason.CURRENCY_NOT_EUR);
        var first = new TransactionRejection("TX-AUDIT", "CORR-FIRST", "ACC-1", new BigDecimal("-1.234"),
                "USD", TransactionType.TRANSFER, NOW, NOW.plusSeconds(1), reasons);
        save(first);
        String original = snapshot("TX-AUDIT");
        save(first);
        save(new TransactionRejection("TX-AUDIT", "CORR-RETRY", "ACC-1", new BigDecimal("-1.2340"),
                "USD", TransactionType.TRANSFER, NOW.plusSeconds(2), NOW.plusSeconds(3), List.of(RejectionReason.PAYLOAD_CONFLICT)));
        assertThat(snapshot("TX-AUDIT")).isEqualTo(original);
        assertThat(count("TX-AUDIT")).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT * FROM transaction_rejections WHERE transaction_id = 'TX-AUDIT'");
        assertThat((Number) row.get("id")).isNotNull();
        assertThat(row.get("correlation_id")).isEqualTo("CORR-FIRST");
        assertThat(row.get("account_id")).isEqualTo("ACC-1");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("-1.234");
        assertThat(row.get("currency")).isEqualTo("USD");
        assertThat(row.get("type")).isEqualTo("TRANSFER");
        assertThat(jdbc.queryForObject("SELECT received_at FROM transaction_rejections WHERE transaction_id='TX-AUDIT'", OffsetDateTime.class).toInstant()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("SELECT rejected_at FROM transaction_rejections WHERE transaction_id='TX-AUDIT'", OffsetDateTime.class).toInstant()).isEqualTo(NOW.plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT reason_codes::text FROM transaction_rejections WHERE transaction_id='TX-AUDIT'", String.class))
                .isEqualTo("{AMOUNT_NOT_POSITIVE,CURRENCY_NOT_EUR}");
        save(new TransactionRejection("TX-AUDIT", "CORR-CHANGED", "ACC-1", new BigDecimal("-2.00"),
                "USD", TransactionType.TRANSFER, NOW, NOW, reasons));
        assertThat(count("TX-AUDIT")).isEqualTo(2);
    }

    @Test
    void nullBusinessValuesDeduplicateAndEmptyReasonsAreForbidden() {
        var rejection = new TransactionRejection("TX-NULL", "CORR-NULL", "ACC-1", null, null, null,
                NOW, NOW, List.of(RejectionReason.AMOUNT_NOT_POSITIVE, RejectionReason.CURRENCY_NOT_EUR, RejectionReason.TYPE_NOT_TRANSFER));
        save(rejection);
        save(rejection);
        assertThat(count("TX-NULL")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE transaction_rejections SET reason_codes = '{}' WHERE transaction_id='TX-NULL'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private static void save(TransactionRejection rejection) { transaction.executeWithoutResult(status -> store.save(rejection)); }
    private static int count(String id) { return jdbc.queryForObject("SELECT count(*) FROM transaction_rejections WHERE transaction_id=?", Integer.class, id); }
    private static String snapshot(String id) { return jdbc.queryForObject("SELECT row_to_json(r)::text FROM transaction_rejections r WHERE transaction_id=?", String.class, id); }
}
