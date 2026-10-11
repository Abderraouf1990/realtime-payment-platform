package com.aayadi.payment.assistant;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import static com.aayadi.payment.assistant.DiagnosisRunner.Currency.*;
import static org.assertj.core.api.Assertions.*;

class EvaluationPreparationTests {
    private static BigDecimal money(String value) { return new BigDecimal(value); }
    @Test void enforcesPerCallRunCurrencyAndAttemptLimits() {
        var budget = new EvaluationBudget(money("0.10"), money("0.20"), 2, EUR);
        assertThatThrownBy(() -> budget.reserve(money("0.11"), EUR)).hasMessage("BUDGET_UNAVAILABLE");
        assertThatThrownBy(() -> budget.reserve(money("0.10"), USD)).hasMessage("BUDGET_UNAVAILABLE");
        budget.reserve(money("0.10"), EUR); budget.reserve(money("0.10"), EUR);
        assertThatThrownBy(() -> budget.reserve(BigDecimal.ZERO, EUR)).hasMessage("BUDGET_UNAVAILABLE");
        var run = new EvaluationBudget(money("0.10"), money("0.15"), 3, EUR);
        run.reserve(money("0.10"), EUR);
        assertThatThrownBy(() -> run.reserve(money("0.10"), EUR)).hasMessage("BUDGET_UNAVAILABLE");
    }
    @Test void settlesKnownCostButUnknownAndPostDispatchFailureRetainReservation() {
        var budget = new EvaluationBudget(money("0.10"), money("0.30"), 3, EUR);
        try (var known = budget.reserve(money("0.10"), EUR)) {
            known.dispatched(); known.settle(money("0.02"), EUR);
            assertThatThrownBy(() -> known.settle(BigDecimal.ZERO, EUR)).hasMessage("INVALID_RESERVATION_STATE");
        }
        try (var unknown = budget.reserve(money("0.10"), EUR)) { unknown.dispatched(); unknown.settle(null, EUR); }
        try (var failed = budget.reserve(money("0.10"), EUR)) { failed.dispatched(); }
        assertThat(budget.snapshot().committedOrReserved()).isEqualByComparingTo("0.22");
    }
    @Test void cancelledBeforeDispatchReleasesMoneyButDoesNotResetAttemptCounter() {
        var budget = new EvaluationBudget(money("0.10"), money("0.10"), 1, EUR);
        try (var cancelled = budget.reserve(money("0.10"), EUR)) { }
        assertThat(budget.snapshot().committedOrReserved()).isZero();
        assertThat(budget.snapshot().reservations()).isEqualTo(1);
        assertThatThrownBy(() -> budget.reserve(BigDecimal.ZERO, EUR)).hasMessage("BUDGET_UNAVAILABLE");
    }
    @Test void underestimatedBoundHaltsFutureCallsAndPreservesActualOverrun() {
        var budget = new EvaluationBudget(money("0.10"), money("0.10"), 3, EUR);
        try (var reservation = budget.reserve(money("0.10"), EUR)) {
            reservation.dispatched();
            assertThatThrownBy(() -> reservation.settle(money("0.15"), EUR)).hasMessage("UPPER_BOUND_BREACHED");
        }
        assertThat(budget.snapshot().breached()).isTrue();
        assertThat(budget.snapshot().committedOrReserved()).isEqualByComparingTo("0.15");
        assertThatThrownBy(() -> budget.reserve(BigDecimal.ZERO, EUR)).hasMessage("BUDGET_UNAVAILABLE");
    }
    @Test void concurrentReservationsCannotSpendTheSameRunBalance() throws Exception {
        var budget = new EvaluationBudget(money("0.10"), money("0.10"), 10, EUR);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> { start.await(); try { budget.reserve(money("0.10"), EUR); return true; }
                catch (IllegalStateException denied) { return false; } };
            var a = executor.submit(attempt); var b = executor.submit(attempt); start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(budget.snapshot().committedOrReserved()).isEqualByComparingTo("0.10");
    }
    @Test void percentileUsesNearestRankAndEmptySamplesRemainUnknown() {
        var latency = EvaluationStatistics.latency(List.of(100L, 1L, 3L, 2L));
        assertThat(latency).isEqualTo(new EvaluationStatistics.Latency(4, 2L, 100L));
        assertThat(EvaluationStatistics.latency(List.of())).isEqualTo(new EvaluationStatistics.Latency(0, null, null));
        assertThatThrownBy(() -> EvaluationStatistics.latency(List.of(-1L))).hasMessage("INVALID_LATENCY");
    }
}
