package com.aayadi.payment.assistant;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** In-process boundary. Principal must be supplied by a trusted identity adapter, never request JSON. */
public final class ContextBuilder {
    public record Scope(String incident, String service) {}
    public record Principal(String subject, Instant expiresAt, Set<String> roles, Set<Scope> scopes) {
        public Principal { roles = Set.copyOf(roles); scopes = Set.copyOf(scopes); }
        @Override public String toString() { return "Principal[redacted]"; }
    }
    public record Request(Scope scope, String question) {
        @Override public String toString() { return "Request[redacted]"; }
    }
    public enum Kind { METRICS, HEALTH, PROCESSING, TRACE, OPERATOR }
    public record Snapshot(Scope scope, Instant at, Kind kind, Map<String, Object> fields) {
        public Snapshot { fields = Collections.unmodifiableMap(new HashMap<>(fields)); }
        @Override public String toString() { return "Snapshot[redacted]"; }
    }
    public record Evidence(String id, String incidentAlias, String serviceAlias, Instant at,
                           Kind kind, String provenance, Map<String, Object> fields) {}
    public record Excerpt(String sourceId, String path, String sha256, int firstLine, int lastLine,
                          String text, String trust) {}
    public record Bundle(String version, Instant evaluatedAt, List<String> queryTerms,
                         List<Evidence> evidence, List<Excerpt> excerpts) {}
    public record Result(String status, String reason, Bundle context) {}
    @FunctionalInterface public interface EvidenceReader { List<Snapshot> read(Scope scope); }
    @FunctionalInterface public interface SourceReader { List<Excerpt> retrieve(Set<String> terms); }

    private final Clock clock;
    private final EvidenceReader evidence;
    private final SourceReader sources;
    public ContextBuilder(Clock clock, EvidenceReader evidence, SourceReader sources) {
        this.clock = Objects.requireNonNull(clock);
        this.evidence = Objects.requireNonNull(evidence);
        this.sources = Objects.requireNonNull(sources);
    }

    public Result build(Principal principal, Request request) {
        Instant now = clock.instant();
        // No adapter calls, including manifest reads, before authorization.
        if (principal == null || principal.subject() == null || principal.subject().isBlank()
                || principal.expiresAt() == null || !principal.expiresAt().isAfter(now)
                || !principal.roles().contains("incident-reader") || request == null
                || request.scope() == null || !principal.scopes().contains(request.scope()))
            return new Result("denied", "ACCESS_DENIED", null);
        if (request.question() == null || request.question().length() > 2000)
            return new Result("rejected", "INVALID_REQUEST", null);
        try {
            var safe = EvidenceFilter.filter(evidence.read(request.scope()), request.scope(), now);
            var terms = new TreeSet<>(EvidenceFilter.terms(request.question()));
            for (var item : safe) {
                terms.addAll(EvidenceFilter.terms(item.kind().name()));
                for (var field : item.fields().entrySet()) {
                    terms.addAll(EvidenceFilter.terms(field.getKey().replaceAll("([a-z])([A-Z])", "$1 $2")));
                    if (field.getValue() instanceof String value && !field.getKey().endsWith("Alias"))
                        terms.addAll(EvidenceFilter.terms(value));
                }
            }
            var excerpts = List.copyOf(sources.retrieve(Collections.unmodifiableSet(terms)));
            if (excerpts.size() > 6 || excerpts.stream().anyMatch(e -> e.text().length() > 1800))
                return new Result("rejected", "INVALID_SOURCES", null);
            return new Result("ready", "READ_ONLY_CONTEXT", new Bundle("1", now, List.copyOf(terms), safe, excerpts));
        } catch (EvidenceFilter.InvalidEvidence e) {
            return new Result("rejected", "INVALID_EVIDENCE", null);
        } catch (RuntimeException e) {
            // Do not expose file paths, diagnostic payloads or nested exception messages.
            return new Result("rejected", "CONTEXT_UNAVAILABLE", null);
        }
    }
}
