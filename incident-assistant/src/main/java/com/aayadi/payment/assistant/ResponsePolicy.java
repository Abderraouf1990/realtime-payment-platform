package com.aayadi.payment.assistant;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Closed schema plus a conservative fixed-language output policy, not a generic prose sanitizer. */
public final class ResponsePolicy {
    public static final class InvalidResponse extends RuntimeException {
        InvalidResponse() { super("INVALID_RESPONSE"); }
    }
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> KEYWORDS = Set.of("type", "properties", "required", "additionalProperties",
            "items", "enum", "minItems", "minLength", "maxLength");
    private static final Map<String, String> SUMMARIES = Map.of(
            "idle", "Fresh zero lag is consistent with idle consumption.",
            "stopped_backlog", "Fresh backlog is observed with a stopped listener.",
            "observation_unavailable", "Current observation is unavailable.",
            "counter_reset", "An attempt counter reset is observed.",
            "attempt_not_commit", "An accepted attempt alone does not prove a Kafka offset commit.",
            "trace_incomplete", "Trace export is unavailable; payment outcome is not established.",
            "unknown", "The supplied context does not support a diagnosis.");
    private static final String UNCERTAINTY = "This response is limited to the supplied evidence; no root cause or payment decision is inferred.";
    private static final String CHECK = "Inspect fresh authorized evidence and the cited runbooks; recovery remains a human responsibility.";
    private final JsonNode schema;
    public ResponsePolicy(Path schemaFile) throws Exception {
        schema = JSON.readTree(Files.readString(schemaFile));
    }

    /** Reference wire format for adapter developers and deterministic fakes; contains no case answers. */
    public static ObjectNode proposal(String diagnosis, List<String> evidenceIds, List<String> sourceIds) {
        if (!SUMMARIES.containsKey(diagnosis)) throw new IllegalArgumentException("UNSUPPORTED_DIAGNOSIS");
        var out = JSON.createObjectNode();
        out.put("version", "1").put("status", diagnosis.equals("unknown") ? "abstained" : "diagnosed")
                .put("diagnosis", diagnosis).put("summary", SUMMARIES.get(diagnosis));
        var findings = out.putArray("findings");
        if (!diagnosis.equals("unknown")) {
            var finding = findings.addObject().put("claim", SUMMARIES.get(diagnosis));
            var evidence = finding.putArray("evidenceIds"); evidenceIds.forEach(evidence::add);
            var sources = finding.putArray("sourceIds"); sourceIds.forEach(sources::add);
        }
        out.putArray("uncertainty").add(UNCERTAINTY);
        out.putArray("nextChecks").add(CHECK);
        out.put("abstentionReason", diagnosis.equals("unknown") ? SUMMARIES.get("unknown") : "");
        return out;
    }

    public JsonNode validate(String raw, ContextBuilder.Bundle context) {
        try {
            require(raw != null && raw.length() <= 16000);
            var value = JSON.readTree(raw); validateSchema(schema, value);
            String code = value.get("diagnosis").asString(); require(SUMMARIES.containsKey(code));
            var evidenceIds = new ArrayList<String>(); var sourceIds = new ArrayList<String>();
            var evidenceById = new HashMap<String, ContextBuilder.Evidence>();
            context.evidence().forEach(e -> evidenceById.put(e.id(), e));
            var allowedSources = new HashSet<String>(); context.excerpts().forEach(e -> allowedSources.add(e.sourceId()));
            if (!code.equals("unknown")) {
                require(value.get("findings").size() == 1);
                var finding = value.get("findings").get(0);
                finding.get("evidenceIds").forEach(id -> evidenceIds.add(id.asString()));
                finding.get("sourceIds").forEach(id -> sourceIds.add(id.asString()));
                require(new HashSet<>(evidenceIds).size() == evidenceIds.size() && new HashSet<>(sourceIds).size() == sourceIds.size());
                require(evidenceById.keySet().containsAll(evidenceIds) && allowedSources.containsAll(sourceIds));
                var cited = evidenceIds.stream().map(evidenceById::get).toList();
                require(supports(code, cited, context) && !contradictory(context.evidence()));
            }
            // Exact canonical comparison rejects arbitrary prose, credentials, Unicode tricks and action requests.
            require(value.equals(proposal(code, evidenceIds, sourceIds)));
            return value;
        } catch (Exception e) { throw new InvalidResponse(); }
    }

    private static boolean supports(String code, List<ContextBuilder.Evidence> cited, ContextBuilder.Bundle context) {
        if (cited.stream().anyMatch(e -> e.at().isAfter(context.evaluatedAt())
                || Duration.between(e.at(), context.evaluatedAt()).compareTo(Duration.ofSeconds(15)) > 0)) return false;
        boolean fresh = cited.stream().anyMatch(e -> number(e, "available", 1) && age(e)
                && e.kind() == ContextBuilder.Kind.METRICS);
        if (Set.of("idle", "stopped_backlog").contains(code) && context.evidence().stream().anyMatch(e ->
                number(e, "up", 0) || number(e, "available", 0))) return false;
        return switch (code) {
            case "idle" -> fresh && cited.stream().anyMatch(e -> number(e, "lag", 0) && number(e, "available", 1) && age(e))
                    && cited.stream().anyMatch(e -> "RUNNING".equals(e.fields().get("listener")));
            case "stopped_backlog" -> fresh && cited.stream().anyMatch(e -> positive(e, "lag") && number(e, "available", 1) && age(e))
                    && cited.stream().anyMatch(e -> "STOPPED".equals(e.fields().get("listener")));
            case "observation_unavailable" -> cited.stream().anyMatch(e -> number(e, "up", 0) || number(e, "available", 0));
            case "counter_reset" -> cited.stream().anyMatch(e -> positive(e, "resetCount"));
            case "attempt_not_commit" -> cited.stream().anyMatch(e -> positive(e, "accepted") || "accepted".equals(e.fields().get("outcome")));
            case "trace_incomplete" -> cited.stream().anyMatch(e -> Boolean.FALSE.equals(e.fields().get("exportAvailable")));
            default -> false;
        };
    }
    private static boolean contradictory(List<ContextBuilder.Evidence> evidence) {
        var values = new HashMap<String, Object>();
        for (var item : evidence) for (var field : item.fields().entrySet()) {
            String key = item.kind() + ":" + item.at() + ":" + field.getKey();
            Object previous = values.putIfAbsent(key, field.getValue());
            if (previous != null && !previous.equals(field.getValue())) return true;
        }
        return false;
    }
    private static boolean age(ContextBuilder.Evidence e) {
        return e.fields().get("observationAgeSeconds") instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 15;
    }
    private static boolean number(ContextBuilder.Evidence e, String key, double expected) {
        return e.fields().get(key) instanceof Number n && n.doubleValue() == expected;
    }
    private static boolean positive(ContextBuilder.Evidence e, String key) {
        return e.fields().get(key) instanceof Number n && n.doubleValue() > 0;
    }
    private static void require(boolean valid) { if (!valid) throw new InvalidResponse(); }
    private static void validateSchema(JsonNode schema, JsonNode value) {
        require(value != null);
        for (var field : schema.properties()) require(KEYWORDS.contains(field.getKey()));
        switch (schema.get("type").asString()) {
            case "object" -> {
                require(value.isObject() && !schema.get("additionalProperties").asBoolean());
                for (var key : schema.get("required")) require(value.has(key.asString()));
                for (var field : value.properties()) {
                    require(schema.get("properties").has(field.getKey()));
                    validateSchema(schema.get("properties").get(field.getKey()), field.getValue());
                }
            }
            case "array" -> {
                require(value.isArray() && value.size() >= schema.path("minItems").asInt(0) && value.size() <= 32);
                for (var item : value) validateSchema(schema.get("items"), item);
            }
            case "string" -> {
                require(value.isString()); int length = value.asString().codePointCount(0, value.asString().length());
                require(length >= schema.path("minLength").asInt(0) && length <= schema.path("maxLength").asInt(Integer.MAX_VALUE));
            }
            default -> throw new InvalidResponse();
        }
        if (schema.has("enum")) { boolean found = false; for (var v : schema.get("enum")) found |= v.equals(value); require(found); }
    }
}
