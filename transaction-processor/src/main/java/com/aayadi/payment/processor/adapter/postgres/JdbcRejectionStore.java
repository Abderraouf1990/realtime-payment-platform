package com.aayadi.payment.processor.adapter.postgres;

import com.aayadi.payment.processor.application.RejectionStore;
import com.aayadi.payment.processor.domain.TransactionRejection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.ZoneOffset;

@Repository
public class JdbcRejectionStore implements RejectionStore {
    private final JdbcTemplate jdbc;

    public JdbcRejectionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void save(TransactionRejection rejection) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            var reasons = connection.createArrayOf("text",
                    rejection.reasonCodes().stream().map(Enum::name).toArray(String[]::new));
            try (var statement = connection.prepareStatement("""
                    INSERT INTO transaction_rejections
                        (transaction_id, correlation_id, account_id, amount, currency, type,
                         received_at, rejected_at, reason_codes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT ON CONSTRAINT uk_transaction_rejections_business_event DO NOTHING
                    """)) {
                statement.setString(1, rejection.transactionId());
                statement.setString(2, rejection.correlationId());
                statement.setString(3, rejection.accountId());
                statement.setBigDecimal(4, rejection.amount());
                statement.setString(5, rejection.currency());
                statement.setString(6, rejection.type() == null ? null : rejection.type().name());
                statement.setObject(7, rejection.receivedAt().atOffset(ZoneOffset.UTC));
                statement.setObject(8, rejection.rejectedAt().atOffset(ZoneOffset.UTC));
                statement.setArray(9, reasons);
                statement.executeUpdate();
            } finally {
                reasons.free();
            }
            return null;
        });
    }
}
