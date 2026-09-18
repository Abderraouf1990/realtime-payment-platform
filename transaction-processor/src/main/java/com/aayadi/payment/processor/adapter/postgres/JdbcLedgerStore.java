package com.aayadi.payment.processor.adapter.postgres;

import com.aayadi.payment.processor.application.LedgerStore;
import com.aayadi.payment.processor.domain.LedgerEntry;
import com.aayadi.payment.processor.domain.BusinessPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class JdbcLedgerStore implements LedgerStore {
    private final JdbcTemplate jdbc;

    public JdbcLedgerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BusinessPayload> findPayload(String transactionId) {
        return jdbc.query("""
                SELECT account_id, amount, currency, type
                FROM ledger_transactions WHERE transaction_id = ?
                """, (row, index) -> new BusinessPayload(row.getString("account_id"), row.getBigDecimal("amount"),
                row.getString("currency"), row.getString("type")), transactionId).stream().findFirst();
    }

    @Override
    @Transactional
    public Outcome save(LedgerEntry entry) {
        int inserted = jdbc.update("""
                INSERT INTO ledger_transactions
                    (transaction_id, correlation_id, account_id, amount, currency, type, received_at, processed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT uk_ledger_transactions_transaction_id DO NOTHING
                """, entry.transactionId(), entry.correlationId(), entry.accountId(), entry.amount(),
                entry.currency(), entry.type().name(), entry.receivedAt().atOffset(ZoneOffset.UTC),
                entry.processedAt().atOffset(ZoneOffset.UTC));
        if (inserted == 1) {
            return Outcome.INSERTED;
        }
        // The pre-read is an optimization, never the uniqueness guarantee. Re-check after a race.
        var existing = findPayload(entry.transactionId())
                .orElseThrow(() -> new IllegalStateException("Conflicting ledger row disappeared"));
        // Preserve the first committed correlation ID and timestamps on redelivery.
        return existing.matches(entry.businessPayload()) ? Outcome.DUPLICATE : Outcome.CONFLICT;
    }
}
