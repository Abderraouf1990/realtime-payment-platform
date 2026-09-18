package com.aayadi.payment.processor;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class LedgerMigrationTests {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6");

    private static final OffsetDateTime RECEIVED_AT = OffsetDateTime.parse("2026-09-18T10:00:00.123456+02:00");
    private static final OffsetDateTime PROCESSED_AT = RECEIVED_AT.plusSeconds(1);
    private static final BigDecimal AMOUNT = new BigDecimal("999999999999999.99");
    private static Flyway flyway;

    @BeforeAll
    static void migrate() {
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
    }

    @Test
    void appliesVersionOneInDefaultSchemaAndDoesNotReapplyIt() throws SQLException {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        try (var connection = connection(); var statement = connection.createStatement()) {
            try (var columns = statement.executeQuery("""
                    SELECT data_type, numeric_precision, numeric_scale
                    FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'ledger_transactions' AND column_name = 'amount'
                    """)) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getString("data_type")).isEqualTo("numeric");
                assertThat(columns.getInt("numeric_precision")).isEqualTo(17);
                assertThat(columns.getInt("numeric_scale")).isEqualTo(2);
            }
            try (var constraints = statement.executeQuery("""
                    SELECT constraint_type FROM information_schema.table_constraints
                    WHERE table_schema = current_schema() AND table_name = 'ledger_transactions'
                      AND constraint_name = 'uk_ledger_transactions_transaction_id'
                    """)) {
                assertThat(constraints.next()).isTrue();
                assertThat(constraints.getString(1)).isEqualTo("UNIQUE");
            }
        }
    }

    @Test
    void storesAllFieldsWithGeneratedIdExactAmountAndTimestampInstants() throws SQLException {
        try (var connection = connection()) {
            long id = insert(connection, "TX-ROUNDTRIP");
            assertThat(id).isPositive();
            try (var statement = connection.prepareStatement("SELECT * FROM ledger_transactions WHERE id = ?")) {
                statement.setLong(1, id);
                try (var row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("transaction_id")).isEqualTo("TX-ROUNDTRIP");
                    assertThat(row.getString("correlation_id")).isEqualTo("CORR-1");
                    assertThat(row.getString("account_id")).isEqualTo("ACC-1");
                    assertThat(row.getBigDecimal("amount")).isEqualByComparingTo(AMOUNT);
                    assertThat(row.getString("currency")).isEqualTo("EUR");
                    assertThat(row.getString("type")).isEqualTo("TRANSFER");
                    assertThat(row.getObject("received_at", OffsetDateTime.class).toInstant())
                            .isEqualTo(RECEIVED_AT.toInstant());
                    assertThat(row.getObject("processed_at", OffsetDateTime.class).toInstant())
                            .isEqualTo(PROCESSED_AT.toInstant());
                }
            }
        }
    }

    @Test
    void rejectsDuplicateTransactionIdAndKeepsOneRow() throws SQLException {
        try (var connection = connection()) {
            insert(connection, "TX-DUPLICATE");
            SQLException failure = assertThrows(SQLException.class, () -> insert(connection, "TX-DUPLICATE"));
            assertThat(failure.getSQLState()).isEqualTo("23505");
            try (var statement = connection.createStatement();
                 var count = statement.executeQuery("SELECT count(*) FROM ledger_transactions WHERE transaction_id = 'TX-DUPLICATE'")) {
                assertThat(count.next()).isTrue();
                assertThat(count.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void rejectsNullTransactionId() throws SQLException {
        try (var connection = connection()) {
            SQLException failure = assertThrows(SQLException.class, () -> insert(connection, null));
            assertThat(failure.getSQLState()).isEqualTo("23502");
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long insert(Connection connection, String transactionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO ledger_transactions
                    (transaction_id, correlation_id, account_id, amount, currency, type, received_at, processed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """)) {
            statement.setString(1, transactionId);
            statement.setString(2, "CORR-1");
            statement.setString(3, "ACC-1");
            statement.setBigDecimal(4, AMOUNT);
            statement.setString(5, "EUR");
            statement.setString(6, "TRANSFER");
            statement.setObject(7, RECEIVED_AT);
            statement.setObject(8, PROCESSED_AT);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }
}
