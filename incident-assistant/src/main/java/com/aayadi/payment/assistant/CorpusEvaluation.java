package com.aayadi.payment.assistant;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** Synthetic-corpus harness; gold answers are evaluator-only, never inference input. */
public final class CorpusEvaluation {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private CorpusEvaluation() {}

    static ObjectNode evaluate(Path root, DiagnosisRunner.InferencePort port, DiagnosisRunner.Pricing pricing,
                               BooleanSupplier halted, Consumer<ObjectNode> checkpoint) throws Exception {
        var corpus = JSON.readTree(Files.readString(root.resolve("docs/assistant/v1/cases.json"))).get("cases");
        var fixtures = JSON.readTree(Files.readString(root.resolve("docs/assistant/v1/context-fixtures.json"))).get("cases");
        if (corpus.size() != 18 || fixtures.size() != 18) throw new IllegalArgumentException("INVALID_CORPUS");
        var report = JSON.createObjectNode().put("promptVersion", OpenAiInference.PROMPT_VERSION)
                .put("policyVersion", "restricted-output-v1").put("startedAt", Instant.now().toString())
                .put("repetitions", 3).put("latencyDefinition", "context + token count + generation + output validation; nearest-rank percentiles");
        report.putNull("humanCorrectness"); report.putNull("passageGroundedness");
        report.put("qualityWarning", "Typed projections omit original prose; code/status agreement is not semantic correctness. Human review pending.");
        var hashes = report.putObject("revisionHashes");
        for (String path : List.of("docs/assistant/v1/cases.json", "docs/assistant/v1/context-fixtures.json",
                "docs/assistant/v1/sources.json", "docs/assistant/v1/response.schema.json", "docs/assistant/v1/openai-prompt.txt"))
            hashes.put(path, hash(Files.readString(root.resolve(path)).replace("\r\n", "\n")));
        hashes.put("effectiveInstructions", hash(OpenAiInference.instructions(root)));
        try (var paths = Files.walk(root.resolve("incident-assistant/src/main/java"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList())
                hashes.put(root.relativize(path).toString().replace('\\', '/'), hash(Files.readString(path).replace("\r\n", "\n")));
        }
        var rows = report.putArray("cases"); var latencies = new ArrayList<Long>();
        java.math.BigDecimal knownCost = java.math.BigDecimal.ZERO;
        int unknownCostCalls = 0;
        var policy = new ResponsePolicy(root.resolve("docs/assistant/v1/response.schema.json"));
        for (int i = 0; i < corpus.size(); i++) {
            var item = corpus.get(i); var fixture = fixtures.get(i);
            String id = item.get("id").asString();
            if (!id.equals(fixture.get("caseId").asString())) throw new IllegalArgumentException("INVALID_CORPUS");
            var input = item.get("input"); var now = Instant.parse(input.get("evaluatedAt").asString());
            var scope = new ContextBuilder.Scope("incident-for-" + id, "processor");
            var snapshots = new ArrayList<ContextBuilder.Snapshot>();
            for (var entry : fixture.get("snapshots")) {
                var fields = new HashMap<String, Object>();
                for (var field : entry.get("fields").properties()) {
                    var value = field.getValue(); Object converted;
                    if (value.isString()) converted = value.asString();
                    else if (value.isBoolean()) converted = value.asBoolean();
                    else if (value.isIntegralNumber()) converted = value.asLong();
                    else converted = value.asDouble();
                    fields.put(field.getKey(), converted);
                }
                snapshots.add(new ContextBuilder.Snapshot(scope, now, ContextBuilder.Kind.valueOf(entry.get("kind").asString()), fields));
            }
            var builder = new ContextBuilder(Clock.fixed(now, ZoneOffset.UTC), s -> snapshots, new ManifestSources(root));
            var principal = input.get("authenticated").asBoolean() ? new ContextBuilder.Principal("fixture-user",
                    now.plusSeconds(60), Set.of("incident-reader"), input.get("authorized").asBoolean() ? Set.of(scope) : Set.of()) : null;
            var request = new ContextBuilder.Request(scope, input.get("question").asString());
            var context = builder.build(principal, request);
            if (!context.status().equals(fixture.get("boundaryStatus").asString())) throw new IllegalStateException("BOUNDARY_REGRESSION");
            for (int repetition = 1; repetition <= 3; repetition++) {
                var row = rows.addObject().put("caseId", id).put("repetition", repetition).put("contextStatus", context.status());
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                DiagnosisRunner.Result result;
                if (context.status().equals("ready") && halted.getAsBoolean()) {
                    result = new DiagnosisRunner.Result("not_run", "CAMPAIGN_HALTED", null, null, 0);
                } else try (var runner = new DiagnosisRunner(builder, policy, (bundle, timeout) -> {
                    calls.incrementAndGet(); return port.infer(bundle, timeout);
                }, Duration.ofSeconds(30), pricing)) {
                    result = runner.run(principal, request);
                }
                row.put("adapterInvocations", calls.get()).put("runnerStatus", result.status()).put("reason", result.reason())
                        .put("elapsedMillis", result.elapsedMillis());
                row.set("usage", JSON.valueToTree(result.usage())); row.set("response", result.response());
                row.put("accessBoundaryPassed", context.status().equals("ready") || calls.get() == 0);
                row.put("outputValidated", result.status().equals("completed"));
                row.putNull("humanCorrectness"); row.putNull("passageGroundedness");
                var expected = item.get("expected").get("response");
                row.put("expectedDiagnosis", expected.get("diagnosis").asString()).put("expectedStatus", expected.get("status").asString());
                if (result.response() != null) row.put("codeAndStatusAgreement", expected.get("diagnosis").equals(result.response().get("diagnosis"))
                        && expected.get("status").equals(result.response().get("status")));
                else row.putNull("codeAndStatusAgreement");
                if (context.context() != null) {
                    // Filtered context is retained locally for passage review, without identities or gold prompt data.
                    row.set("reviewContext", JSON.valueToTree(context.context()));
                    var retrieved = new HashSet<String>(); context.context().excerpts().forEach(e -> retrieved.add(e.sourceId()));
                    var missing = row.putArray("missingSourceIds");
                    for (var source : item.get("expected").get("policySourceIds"))
                        if (!retrieved.contains(source.asString())) missing.add(source.asString());
                }
                if (calls.get() > 0) latencies.add(result.elapsedMillis());
                if (calls.get() > 0) {
                    if (result.usage() == null || result.usage().estimatedCost() == null) unknownCostCalls++;
                    else knownCost = knownCost.add(result.usage().estimatedCost());
                }
                report.put("knownCostSubtotal", knownCost).put("unknownCostInvocations", unknownCostCalls);
                if (unknownCostCalls == 0 && !latencies.isEmpty()) report.put("totalEstimatedCost", knownCost);
                else report.putNull("totalEstimatedCost");
                report.set("latency", JSON.valueToTree(EvaluationStatistics.latency(latencies)));
                checkpoint.accept(report);
            }
        }
        report.put("finishedAt", Instant.now().toString());
        return report;
    }
    static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    /** One durable marker for the approved campaign, outside target so Maven clean cannot reset it. */
    static void claimCampaign(Path marker) throws Exception {
        try (var channel = FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode("Approved M5 campaign claimed. Do not delete or rerun without reconciling spend.\n");
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }
    public static void main(String[] args) {
        try {
            if (args.length != 3 || !args[1].matches("[a-f0-9]{40}") || !"--run-approved-m5".equals(args[2]))
                throw new IllegalArgumentException();
            Path root = Path.of(args[0]).toRealPath();
            String key = System.getenv("OPENAI_API_KEY");
            if (key == null || key.isBlank()) {
                System.err.println("MISSING_API_CREDENTIAL: configure OPENAI_API_KEY locally; no request sent."); System.exit(2); return;
            }
            // Expired price assumptions require review, not silent execution months later.
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (today.isBefore(OpenAiInference.PRICING.asOf()) || today.isAfter(OpenAiInference.PRICING.asOf().plusDays(7)))
                throw new IllegalStateException();
            Files.createDirectories(root.resolve("artifacts"));
            var budget = new EvaluationBudget(new java.math.BigDecimal("0.05"), new java.math.BigDecimal("2.00"), 39,
                    DiagnosisRunner.Currency.USD);
            try (var adapter = new OpenAiInference(key, root, budget)) {
                claimCampaign(root.resolve("artifacts/m5-openai-2026-10-11.started"));
                Path output = root.resolve("artifacts/m5-openai-evaluation.json");
                Consumer<ObjectNode> checkpoint = report -> {
                    report.put("mode", "real-openai").put("model", OpenAiInference.MODEL).put("baseCommit", args[1])
                            .put("workingTree", "See revisionHashes; base commit alone does not identify uncommitted source")
                            .put("generationRequests", adapter.generations()).put("tokenCountRequests", adapter.countRequests());
                    report.set("pricing", JSON.valueToTree(OpenAiInference.PRICING));
                    report.set("budget", JSON.valueToTree(budget.snapshot()));
                    try { Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)); }
                    catch (Exception e) { throw new IllegalStateException("REPORT_WRITE_FAILED"); }
                };
                var report = evaluate(root, adapter, OpenAiInference.PRICING, adapter::halted, checkpoint);
                checkpoint.accept(report);
                System.out.println("Evaluation report: artifacts/m5-openai-evaluation.json (human review pending).");
            }
        } catch (Exception e) {
            // Never print credential-bearing HTTP errors, provider bodies, or nested causes.
            System.err.println("EVALUATION_STOPPED: check configuration, dated pricing and campaign marker; no automatic retry.");
            System.exit(1);
        }
    }
}
