package com.aayadi.payment.processor.adapter.postgres;

import com.aayadi.payment.processor.application.LedgerStore;
import com.aayadi.payment.processor.domain.LedgerEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.ZoneOffset;

@Repository
public class JdbcLedgerStore implements LedgerStore {
    private final JdbcTemplate jdbc;

    public JdbcLedgerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        Boolean identical = jdbc.queryForObject("""
                SELECT account_id = ? AND amount = ? AND currency = ? AND type = ?
                FROM ledger_transactions WHERE transaction_id = ?
                """, Boolean.class, entry.accountId(), entry.amount(), entry.currency(), entry.type().name(),
                entry.transactionId());
        if (!Boolean.TRUE.equals(identical)) {
            throw new IllegalStateException("Transaction ID conflicts with an existing ledger entry");
        }
        // Preserve the first committed correlation ID and timestamps on redelivery.
        return Outcome.DUPLICATE;
    }
}
