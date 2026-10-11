package com.aayadi.payment.assistant;

import java.math.BigDecimal;
import java.util.Objects;

/** Process-local reservations. A provider adapter must supply a defensible upper bound before dispatch. */
public final class EvaluationBudget {
    public record Snapshot(BigDecimal committedOrReserved, int reservations, boolean breached) {}
    private final BigDecimal perCall;
    private final BigDecimal perRun;
    private final int maximumCalls;
    private final DiagnosisRunner.Currency currency;
    private BigDecimal committed = BigDecimal.ZERO;
    private int calls;
    private boolean breached;

    public EvaluationBudget(BigDecimal perCall, BigDecimal perRun, int maximumCalls, DiagnosisRunner.Currency currency) {
        this.perCall = amount(perCall); this.perRun = amount(perRun);
        this.currency = Objects.requireNonNull(currency); this.maximumCalls = maximumCalls;
        if (perRun.compareTo(perCall) < 0 || maximumCalls < 1) throw new IllegalArgumentException("INVALID_BUDGET");
    }
    public synchronized Reservation reserve(BigDecimal upperBound, DiagnosisRunner.Currency unit) {
        amount(upperBound);
        if (unit != currency || breached || calls >= maximumCalls || upperBound.compareTo(perCall) > 0
                || committed.add(upperBound).compareTo(perRun) > 0)
            throw new IllegalStateException("BUDGET_UNAVAILABLE");
        committed = committed.add(upperBound); calls++;
        return new Reservation(upperBound);
    }
    public synchronized Snapshot snapshot() { return new Snapshot(committed, calls, breached); }

    public final class Reservation implements AutoCloseable {
        private final BigDecimal upperBound;
        private boolean dispatched;
        private boolean closed;
        private Reservation(BigDecimal upperBound) { this.upperBound = upperBound; }

        public void dispatched() {
            synchronized (EvaluationBudget.this) {
                if (closed || dispatched) throw new IllegalStateException("INVALID_RESERVATION_STATE");
                dispatched = true;
            }
        }
        /** Null/unknown usage conservatively retains the entire reservation. */
        public void settle(BigDecimal actual, DiagnosisRunner.Currency unit) {
            synchronized (EvaluationBudget.this) {
                if (closed || !dispatched) throw new IllegalStateException("INVALID_RESERVATION_STATE");
                if (actual == null) { closed = true; return; }
                amount(actual);
                if (unit != currency) throw new IllegalArgumentException("INVALID_CURRENCY");
                committed = committed.subtract(upperBound).add(actual); closed = true;
                if (actual.compareTo(upperBound) > 0) {
                    breached = true;
                    throw new IllegalStateException("UPPER_BOUND_BREACHED");
                }
            }
        }
        /** A pre-dispatch cancellation releases money but still counts toward the run attempt limit. */
        @Override public void close() {
            synchronized (EvaluationBudget.this) {
                if (closed) return;
                if (!dispatched) committed = committed.subtract(upperBound);
                closed = true;
            }
        }
    }
    private static BigDecimal amount(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.precision() > 24 || Math.abs((long) value.scale()) > 12)
            throw new IllegalArgumentException("INVALID_AMOUNT");
        return value;
    }
}
