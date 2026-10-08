package com.aayadi.payment.assistant;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class CorpusRetrievalTests {
    private static final Path ROOT = Path.of(System.getProperty("repository.root"));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void evaluatesAllCasesWithoutFeedingReferenceAnswersOrGoldSourceIdsToRetrieval() throws Exception {
        var corpus = JSON.readTree(Files.readString(ROOT.resolve("docs/assistant/v1/cases.json"))).get("cases");
        var fixtures = JSON.readTree(Files.readString(ROOT.resolve("docs/assistant/v1/context-fixtures.json"))).get("cases");
        assertThat(corpus.size()).isEqualTo(18); assertThat(fixtures.size()).isEqualTo(18);
        var report = new StringBuilder("case,status,expectedSourceCount,retrievedExpectedSourceCount,retrievedSourceIds\n");
        var runnerReport = JSON.createObjectNode();
        runnerReport.put("mode", "deterministic-abstaining-fake");
        runnerReport.putNull("modelQuality"); runnerReport.putNull("humanQuality");
        var runs = runnerReport.putArray("cases");
        var responsePolicy = new ResponsePolicy(ROOT.resolve("docs/assistant/v1/response.schema.json"));
        int inferenceTotal = 0;
        int ready = 0, expectedTotal = 0, foundTotal = 0, anyHit = 0;
        for (int i = 0; i < corpus.size(); i++) {
            var item = corpus.get(i); var fixture = fixtures.get(i);
            assertThat(fixture.get("caseId").asString()).isEqualTo(item.get("id").asString());
            // Only input question, fixed time and independently projected evidence cross the boundary.
            var input = item.get("input"); var now = Instant.parse(input.get("evaluatedAt").asString());
            var scope = new ContextBuilder.Scope("incident-for-" + item.get("id").asString(), "processor");
            var snapshots = new ArrayList<ContextBuilder.Snapshot>();
            for (var entry : fixture.get("snapshots")) {
                var fields = new HashMap<String, Object>();
                for (var field : entry.get("fields").properties()) {
                    var value = field.getValue();
                    Object converted;
                    if (value.isString()) converted = value.asString();
                    else if (value.isBoolean()) converted = value.asBoolean();
                    else if (value.isIntegralNumber()) converted = value.asLong();
                    else converted = value.asDouble();
                    fields.put(field.getKey(), converted);
                }
                snapshots.add(new ContextBuilder.Snapshot(scope, now, ContextBuilder.Kind.valueOf(entry.get("kind").asString()), fields));
            }
            var evidenceCalls = new AtomicInteger(); var sourceCalls = new AtomicInteger();
            var source = new ManifestSources(ROOT);
            var builder = new ContextBuilder(Clock.fixed(now, ZoneOffset.UTC), s -> {
                evidenceCalls.incrementAndGet(); assertThat(s).isEqualTo(scope); return snapshots;
            }, terms -> { sourceCalls.incrementAndGet(); return source.retrieve(terms); });
            // Test-only simulation of an already verified gateway principal; not request identity parsing.
            var principal = input.get("authenticated").asBoolean() ? new ContextBuilder.Principal("fixture-user",
                    now.plusSeconds(60), Set.of("incident-reader"), input.get("authorized").asBoolean() ? Set.of(scope) : Set.of()) : null;
            var request = new ContextBuilder.Request(scope, input.get("question").asString());
            var result = builder.build(principal, request);
            assertThat(result.status()).as(item.get("id").asString()).isEqualTo(fixture.get("boundaryStatus").asString());
            var inferenceCalls = new AtomicInteger();
            DiagnosisRunner.Result diagnosis;
            try (var runner = new DiagnosisRunner(builder, responsePolicy, (modelInput, timeout) -> {
                inferenceCalls.incrementAndGet();
                assertThat(JSON.writeValueAsString(modelInput)).doesNotContain("expected", "mustMention", "mustNotClaim",
                        "FAKE-SECRET-CANARY", "FAKE-ACCOUNT-CANARY", "OVERRIDE-CANARY", "fixture-user", "incident-for-");
                // Deliberately always abstain. No oracle data, case ID, or diagnosis claim in this fake.
                return new DiagnosisRunner.Reply(ResponsePolicy.proposal("unknown", List.of(), List.of()).toString(), null);
            }, Duration.ofSeconds(1), null)) {
                diagnosis = runner.run(principal, request);
            }
            assertThat(inferenceCalls).hasValue(result.status().equals("ready") ? 1 : 0);
            inferenceTotal += inferenceCalls.get();
            var row = runs.addObject().put("caseId", item.get("id").asString())
                    .put("contextStatus", result.status()).put("runnerStatus", diagnosis.status())
                    .put("inferenceCalls", inferenceCalls.get()).put("elapsedMillis", diagnosis.elapsedMillis());
            row.putNull("cost"); row.putNull("modelScore"); row.putNull("humanScore");
            row.put("boundaryPassed", diagnosis.status().equals(result.status().equals("ready") ? "completed" : result.status()));
            assertThat(row.get("boundaryPassed").asBoolean()).isTrue();
            row.put("expectedStatus", item.get("expected").get("response").get("status").asString());
            if (diagnosis.response() != null) row.put("returnedStatus", diagnosis.response().get("status").asString());
            else row.putNull("returnedStatus");
            if (!result.status().equals("ready")) {
                assertThat(sourceCalls).hasValue(0);
                assertThat(evidenceCalls).hasValue(result.status().equals("denied") ? 0 : 2);
                row.putArray("missingSourceIds"); // Not evaluated for contexts denied/rejected before retrieval.
                report.append(item.get("id").asString()).append(',').append(result.status()).append(",0,0,\n");
                continue;
            }
            assertThat(result).isEqualTo(builder.build(principal, request));
            String serialized = JSON.writeValueAsString(result.context());
            assertThat(serialized).doesNotContain("mustMention", "mustNotClaim", "policySourceIds", "boundaryStatus",
                    "FAKE-SECRET-CANARY", "FAKE-ACCOUNT-CANARY", "OVERRIDE-CANARY", "SYSTEM OVERRIDE");
            var retrieved = new TreeSet<String>();
            result.context().excerpts().forEach(e -> retrieved.add(e.sourceId()));
            // Gold IDs are read ONLY after retrieval, by the evaluator.
            var expected = new HashSet<String>();
            for (var id : item.get("expected").get("policySourceIds")) expected.add(id.asString());
            var found = new HashSet<>(expected); found.retainAll(retrieved);
            var missing = new TreeSet<>(expected); missing.removeAll(retrieved);
            var missingIds = row.putArray("missingSourceIds"); missing.forEach(missingIds::add);
            ready++; expectedTotal += expected.size(); foundTotal += found.size(); if (!found.isEmpty()) anyHit++;
            report.append(item.get("id").asString()).append(",ready,").append(expected.size()).append(',')
                    .append(found.size()).append(',').append(String.join("|", retrieved)).append('\n');
        }
        assertThat(ready).isEqualTo(13);
        // Measure coverage honestly; this is not a model-quality gate or an oracle-guided retrieval test.
        report.append("# ready=").append(ready).append("; sourceRecall=").append(foundTotal).append('/')
                .append(expectedTotal).append("; casesWithAnyExpectedSource=").append(anyHit).append('/').append(ready).append('\n');
        Path output = Path.of("target", "retrieval-coverage.csv"); Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString());
        assertThat(inferenceTotal).isEqualTo(13);
        runnerReport.put("casesExercised", 18).put("inferenceCalls", inferenceTotal);
        Files.writeString(Path.of("target", "diagnosis-evaluation.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(runnerReport));
        assertThat(foundTotal).isPositive();
    }
}
