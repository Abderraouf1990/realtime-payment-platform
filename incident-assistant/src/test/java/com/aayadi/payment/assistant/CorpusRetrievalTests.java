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
            if (!result.status().equals("ready")) {
                assertThat(sourceCalls).hasValue(0);
                assertThat(evidenceCalls).hasValue(result.status().equals("denied") ? 0 : 1);
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
        assertThat(foundTotal).isPositive();
    }
}
