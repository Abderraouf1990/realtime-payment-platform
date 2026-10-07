package com.aayadi.payment.e2e;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/** Offline artifact checks, not a model evaluation or a runtime security boundary. */
class IncidentCorpusTests {
    private static final Path ROOT = Path.of(System.getProperty("repository.root")).toAbsolutePath().normalize();
    private static final Path DATA = ROOT.resolve("docs/assistant/v1");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> KEYWORDS = Set.of("type", "properties", "required", "additionalProperties",
            "items", "enum", "minItems", "minLength", "maxLength");

    private JsonNode read(String file) throws Exception { return JSON.readTree(Files.readString(DATA.resolve(file))); }

    @Test void sourceManifestResolvesOnlyVersionedDocsAndDetectsContentDrift() throws Exception {
        var ids = new HashSet<String>();
        for (var source : read("sources.json").get("sources")) {
            assertThat(ids.add(source.get("id").asString())).isTrue();
            var relative = source.get("path").asString();
            assertThat(relative).matches("docs/(adr|runbooks)/[a-z0-9-]+\\.md");
            var path = ROOT.resolve(relative).normalize();
            assertThat(path.startsWith(ROOT.resolve("docs"))).isTrue();
            var normalized = Files.readString(path).replace("\r\n", "\n");
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
            assertThat(source.get("sha256").asString()).as(relative).isEqualTo(digest);
        }
        assertThat(ids).contains("policy", "lag", "collection", "sql-runbook");
    }

    @Test void eighteenCasesHaveValidAnswersResolvableCitationsAndRequiredCoverage() throws Exception {
        var corpus = read("cases.json");
        assertThat(corpus.get("version").asString()).isEqualTo("1");
        assertThat(corpus.get("cases").size()).isEqualTo(18);
        var ids = new HashSet<String>();
        var names = new HashSet<String>();
        var coverage = new HashSet<String>();
        var sources = sourceIds();
        for (var item : corpus.get("cases")) {
            assertThat(ids.add(item.get("id").asString())).isTrue();
            assertThat(names.add(item.get("name").asString())).isTrue();
            coverage.addAll(strings(item.get("tags")));
            var input = item.get("input");
            assertThat(input.get("question").asString()).isNotBlank();
            assertThat(input.get("authenticated").isBoolean()).isTrue();
            assertThat(input.get("authorized").isBoolean()).isTrue();
            var at = Instant.parse(input.get("evaluatedAt").asString());
            var evidenceIds = new HashSet<String>();
            for (var evidence : input.get("evidence")) {
                assertThat(evidenceIds.add(evidence.get("id").asString())).isTrue();
                assertThat(evidence.get("kind").asString()).isEqualTo("synthetic_snapshot");
                assertThat(evidence.get("content").asString()).isNotBlank();
                assertThat(Instant.parse(evidence.get("at").asString())).isBeforeOrEqualTo(at);
            }
            assertThat(sources).containsAll(strings(input.get("retrievedSourceIds")));
            var expected = item.get("expected");
            assertThat(strings(expected.get("policySourceIds"))).isNotEmpty().isSubsetOf(sources);
            assertThat(strings(expected.get("mustMention"))).isNotEmpty();
            assertThat(strings(expected.get("mustNotClaim"))).isNotEmpty();
            validateAnswer(item, expected.get("response"));
            var serialized = expected.get("response").toString();
            assertThat(serialized).doesNotContain("FAKE-SECRET-CANARY", "FAKE-ACCOUNT-CANARY", "OVERRIDE-CANARY");
        }
        assertThat(coverage).contains("idle", "backlog", "manual_recovery", "recovery", "stale", "unavailable",
                "reset", "ack_boundary", "replay", "trace", "conflict", "injection", "redaction",
                "authentication", "authorization", "payment_decision");
    }

    @Test void schemaRejectsUnknownFieldsWrongTypesMissingFieldsAndUnsupportedKeywords() throws Exception {
        var schema = read("response.schema.json");
        ObjectNode answer = (ObjectNode) read("cases.json").get("cases").get(0).get("expected").get("response");
        var extra = answer.deepCopy().put("execute", "restart");
        assertThatThrownBy(() -> validate(schema, extra)).isInstanceOf(AssertionError.class);
        var wrongType = answer.deepCopy().put("findings", "not an array");
        assertThatThrownBy(() -> validate(schema, wrongType)).isInstanceOf(AssertionError.class);
        var missing = answer.deepCopy(); missing.remove("summary");
        assertThatThrownBy(() -> validate(schema, missing)).isInstanceOf(AssertionError.class);
        var unsupported = ((ObjectNode) schema).deepCopy().put("unimplementedKeyword", true);
        assertThatThrownBy(() -> validate(unsupported, answer)).isInstanceOf(AssertionError.class);
    }

    @Test void semanticChecksRejectInventedCitationsEmptyEvidenceAndUnauthorizedDiagnosis() throws Exception {
        var item = read("cases.json").get("cases").get(0);
        ObjectNode answer = (ObjectNode) item.get("expected").get("response");
        var invented = answer.deepCopy();
        ((ObjectNode) invented.get("findings").get(0)).putArray("sourceIds").add("fabricated");
        assertThatThrownBy(() -> validateAnswer(item, invented)).isInstanceOf(AssertionError.class);
        var absent = answer.deepCopy();
        ((ObjectNode) absent.get("findings").get(0)).putArray("evidenceIds").add("E404");
        assertThatThrownBy(() -> validateAnswer(item, absent)).isInstanceOf(AssertionError.class);
        var empty = answer.deepCopy(); empty.putArray("findings");
        assertThatThrownBy(() -> validateAnswer(item, empty)).isInstanceOf(AssertionError.class);
        var unauthorized = ((ObjectNode) item).deepCopy();
        ((ObjectNode) unauthorized.get("input")).put("authorized", false);
        assertThatThrownBy(() -> validateAnswer(unauthorized, answer)).isInstanceOf(AssertionError.class);
        var falseAbstention = answer.deepCopy().put("status", "abstained");
        assertThatThrownBy(() -> validateAnswer(item, falseAbstention)).isInstanceOf(AssertionError.class);
    }

    private Set<String> sourceIds() throws Exception {
        var ids = new HashSet<String>();
        for (var source : read("sources.json").get("sources")) ids.add(source.get("id").asString());
        return ids;
    }

    private void validateAnswer(JsonNode item, JsonNode answer) throws Exception {
        validate(read("response.schema.json"), answer);
        var input = item.get("input");
        var status = answer.get("status").asString();
        if (!input.get("authenticated").asBoolean() || !input.get("authorized").asBoolean()) {
            assertThat(status).isEqualTo("denied");
            assertThat(input.get("evidence").size()).isZero();
            assertThat(input.get("retrievedSourceIds").size()).isZero();
        }
        if (status.equals("diagnosed")) {
            assertThat(answer.get("diagnosis").asString()).isNotEqualTo("unknown");
            assertThat(answer.get("findings").size()).isPositive();
            assertThat(answer.get("abstentionReason").asString()).isEmpty();
        } else {
            assertThat(answer.get("diagnosis").asString()).isEqualTo("unknown");
            assertThat(answer.get("abstentionReason").asString()).isNotBlank();
        }
        if (status.equals("denied")) assertThat(answer.get("findings").size()).isZero();
        var evidenceIds = new HashSet<String>();
        for (var evidence : input.get("evidence")) evidenceIds.add(evidence.get("id").asString());
        for (var finding : answer.get("findings")) {
            assertThat(strings(finding.get("sourceIds"))).isSubsetOf(sourceIds())
                    .isSubsetOf(strings(input.get("retrievedSourceIds")));
            assertThat(strings(finding.get("evidenceIds"))).isSubsetOf(evidenceIds);
        }
    }

    private static Set<String> strings(JsonNode array) {
        assertThat(array).isNotNull();
        assertThat(array.isArray()).isTrue();
        var values = new HashSet<String>();
        for (var item : array) {
            assertThat(item.isString()).isTrue();
            assertThat(item.asString()).isNotBlank();
            assertThat(values.add(item.asString())).as("duplicate array value").isTrue();
        }
        return values;
    }

    private static void validate(JsonNode schema, JsonNode value) {
        for (var property : schema.properties()) assertThat(KEYWORDS).contains(property.getKey());
        assertThat(value).isNotNull();
        switch (schema.get("type").asString()) {
            case "object" -> {
                assertThat(value.isObject()).isTrue();
                for (var required : schema.get("required")) assertThat(value.has(required.asString())).isTrue();
                assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
                for (var property : value.properties()) {
                    assertThat(schema.get("properties").has(property.getKey())).isTrue();
                    validate(schema.get("properties").get(property.getKey()), property.getValue());
                }
            }
            case "array" -> {
                assertThat(value.isArray()).isTrue();
                assertThat(value.size()).isGreaterThanOrEqualTo(schema.path("minItems").asInt(0));
                for (var item : value) validate(schema.get("items"), item);
            }
            case "string" -> {
                assertThat(value.isString()).isTrue();
                assertThat(value.asString().length()).isBetween(schema.path("minLength").asInt(0),
                        schema.path("maxLength").asInt(Integer.MAX_VALUE));
            }
            default -> throw new AssertionError("Unsupported schema type");
        }
        if (schema.has("enum")) assertThat(strings(schema.get("enum"))).contains(value.asString());
    }
}
