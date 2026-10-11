package com.aayadi.payment.assistant;

import java.util.List;

/** Nearest-rank local wall-time statistics; unknown/absent samples are not zero milliseconds. */
public final class EvaluationStatistics {
    private EvaluationStatistics() {}
    public record Latency(int samples, Long p50Millis, Long p95Millis) {}
    public static Latency latency(List<Long> elapsedMillis) {
        if (elapsedMillis.stream().anyMatch(v -> v == null || v < 0)) throw new IllegalArgumentException("INVALID_LATENCY");
        var sorted = elapsedMillis.stream().sorted().toList();
        if (sorted.isEmpty()) return new Latency(0, null, null);
        return new Latency(sorted.size(), sorted.get((int) Math.ceil(sorted.size() * 0.50) - 1),
                sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1));
    }
}
