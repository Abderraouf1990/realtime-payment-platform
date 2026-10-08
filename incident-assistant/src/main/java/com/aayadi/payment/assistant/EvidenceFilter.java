package com.aayadi.payment.assistant;

import java.time.Instant;
import java.util.*;

final class EvidenceFilter {
    static final class InvalidEvidence extends RuntimeException {
        InvalidEvidence() { super("INVALID_EVIDENCE"); }
    }
    private static final Set<String> VOCABULARY = Set.of(("lag idle backlog stopped listener readiness liveness sql "
            + "database postgresql restored restart recovery offset commit acknowledgement accepted duplicate rejected "
            + "failure counter reset replay notification publication trace tracing exporter unavailable missing stale "
            + "observation progress conflict policy access sensitive credential metrics health processing operator "
            + "running paused amount sampling retention denied authentication authorization").split(" "));
    private static final Map<ContextBuilder.Kind, Set<String>> ALLOWED = Map.of(
            ContextBuilder.Kind.METRICS, Set.of("up", "available", "lag", "observationAgeSeconds", "progressAgeSeconds",
                    "accepted", "duplicate", "rejected", "technicalFailure", "resetCount"),
            ContextBuilder.Kind.HEALTH, Set.of("listener", "readiness", "liveness"),
            ContextBuilder.Kind.PROCESSING, Set.of("outcome", "reason", "partition", "offset", "correlationId"),
            ContextBuilder.Kind.TRACE, Set.of("sampling", "exportAvailable", "traceId", "parentId"),
            ContextBuilder.Kind.OPERATOR, Set.of("sqlAvailable", "manualRestart", "committedOffsetDelta", "ledgerCount"));
    static Set<String> terms(String text) {
        var result = new TreeSet<String>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z]+"))
            if (VOCABULARY.contains(token)) result.add(token);
        return result;
    }
    static List<ContextBuilder.Evidence> filter(List<ContextBuilder.Snapshot> input, ContextBuilder.Scope scope, Instant now) {
        if (input == null || input.size() > 16) throw new InvalidEvidence();
        var aliases = new HashMap<String, String>();
        var output = new ArrayList<ContextBuilder.Evidence>();
        for (var item : input) {
            if (item == null || !scope.equals(item.scope()) || item.at() == null || item.at().isAfter(now)
                    || item.kind() == null || item.fields().isEmpty()
                    || !ALLOWED.get(item.kind()).containsAll(item.fields().keySet())) throw new InvalidEvidence();
            var safe = new TreeMap<String, Object>();
            for (var field : new TreeMap<>(item.fields()).entrySet()) {
                String key = field.getKey(); Object value = field.getValue();
                if (Set.of("correlationId", "traceId", "parentId").contains(key)) {
                    if (!(value instanceof String raw) || raw.isBlank() || raw.length() > 128) throw new InvalidEvidence();
                    // Namespace trace relationships together, independently of business correlation.
                    String family = key.equals("correlationId") ? "correlation:" : "trace:";
                    String alias = aliases.computeIfAbsent(family + raw, ignored -> "reference-" + (aliases.size() + 1));
                    safe.put(key.replace("Id", "Alias"), alias);
                } else if (key.equals("listener")) {
                    requireEnum(value, Set.of("STARTING", "RUNNING", "STOPPED", "MISSING", "PAUSED")); safe.put(key, value);
                } else if (key.equals("outcome")) {
                    requireEnum(value, Set.of("accepted", "duplicate", "rejected", "technical_failure")); safe.put(key, value);
                } else if (key.equals("reason")) {
                    requireEnum(value, Set.of("PROCESSING_FAILURE", "PAYLOAD_CONFLICT", "AMOUNT_NOT_POSITIVE",
                            "CURRENCY_NOT_EUR", "TYPE_NOT_TRANSFER")); safe.put(key, value);
                } else if (Set.of("sqlAvailable", "manualRestart", "exportAvailable").contains(key)) {
                    if (!(value instanceof Boolean)) throw new InvalidEvidence(); safe.put(key, value);
                } else {
                    if (!(value instanceof Integer || value instanceof Long || value instanceof Double)) throw new InvalidEvidence();
                    double number = ((Number) value).doubleValue();
                    if (!Double.isFinite(number) || number > 1_000_000_000_000L) throw new InvalidEvidence();
                    if (Set.of("up", "available").contains(key) && number != 0 && number != 1) throw new InvalidEvidence();
                    if (Set.of("readiness", "liveness").contains(key) && number != 200 && number != 503) throw new InvalidEvidence();
                    if (key.equals("sampling") && (number < 0 || number > 1)) throw new InvalidEvidence();
                    boolean ageOrLag = Set.of("lag", "observationAgeSeconds", "progressAgeSeconds").contains(key);
                    if (number < (ageOrLag ? -1 : 0)) throw new InvalidEvidence();
                    if (!key.endsWith("Seconds") && !key.equals("sampling") && number != Math.rint(number)) throw new InvalidEvidence();
                    safe.put(key, value);
                }
            }
            output.add(new ContextBuilder.Evidence("evidence-" + (output.size() + 1), "incident-1", "service-1",
                    item.at(), item.kind(), "injected-read-only-adapter", Collections.unmodifiableMap(safe)));
        }
        return List.copyOf(output);
    }
    private static void requireEnum(Object value, Set<String> allowed) {
        if (!(value instanceof String text) || !allowed.contains(text)) throw new InvalidEvidence();
    }
}
