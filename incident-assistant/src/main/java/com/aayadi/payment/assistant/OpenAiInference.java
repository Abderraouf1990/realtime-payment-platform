package com.aayadi.payment.assistant;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Explicitly constructed, fixed-provider adapter. No retries, tools, redirects or provider text in errors. */
public final class OpenAiInference implements DiagnosisRunner.InferencePort, AutoCloseable {
    public static final String MODEL = "gpt-5.4-mini-2026-03-17";
    public static final String PROMPT_VERSION = "incident-diagnosis-openai-v1";
    public static final DiagnosisRunner.Pricing PRICING = new DiagnosisRunner.Pricing(
            LocalDate.of(2026, 10, 11), DiagnosisRunner.Currency.USD,
            new BigDecimal("0.75"), new BigDecimal("4.50"), new BigDecimal("0.075"));
    public static final int MAX_OUTPUT_TOKENS = 2000;
    private static final BigDecimal CALL_LIMIT = new BigDecimal("0.05");
    private static final URI API = URI.create("https://api.openai.com/v1/");
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final HttpClient client;
    private final URI endpoint;
    private final String key;
    private final String instructions;
    private final EvaluationBudget budget;
    private int generations;
    private int countRequests;
    private boolean halted;

    public OpenAiInference(String key, Path root, EvaluationBudget budget) throws Exception {
        this(key, root, budget, API);
    }
    // Package-private loopback seam for tests. Production has no configurable endpoint.
    OpenAiInference(String key, Path root, EvaluationBudget budget, URI endpoint) throws Exception {
        if (!API.equals(endpoint) && !("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost())
                && endpoint.getUserInfo() == null && endpoint.getQuery() == null && endpoint.getFragment() == null))
            throw new IllegalArgumentException("INVALID_ENDPOINT");
        if (key == null || key.isBlank() || key.contains("\r") || key.contains("\n"))
            throw new IllegalArgumentException("MISSING_API_CREDENTIAL");
        this.key = key; this.endpoint = endpoint; this.budget = Objects.requireNonNull(budget);
        instructions = instructions(root);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    static String instructions(Path root) throws Exception {
        String prompt = Files.readString(root.resolve("docs/assistant/v1/openai-prompt.txt")).replace("\r\n", "\n");
        String schema = Files.readString(root.resolve("docs/assistant/v1/response.schema.json")).replace("\r\n", "\n");
        var templates = JSON.createArrayNode();
        for (String code : List.of("idle", "stopped_backlog", "observation_unavailable", "counter_reset",
                "attempt_not_commit", "trace_incomplete", "unknown"))
            templates.add(ResponsePolicy.proposal(code, List.of("SUPPLIED_EVIDENCE_ID"), List.of("SUPPLIED_SOURCE_ID")));
        return prompt + "\nSchema:\n" + schema + "\nCanonical templates:\n" + templates;
    }
    public synchronized int generations() { return generations; }
    public synchronized int countRequests() { return countRequests; }
    public synchronized boolean halted() { return halted; }

    @Override public synchronized DiagnosisRunner.Reply infer(DiagnosisRunner.Input input, Duration timeout) throws Exception {
        if (halted) throw new IllegalStateException("PROVIDER_HALTED");
        try {
            if (!"restricted-output-v1".equals(input.policyVersion()) || timeout.isNegative() || timeout.isZero()
                    || timeout.compareTo(Duration.ofSeconds(30)) > 0) throw new IllegalArgumentException();
            long deadline = System.nanoTime() + timeout.toNanos();
            var countBody = JSON.createObjectNode().put("model", MODEL).put("instructions", instructions)
                    .put("input", JSON.writeValueAsString(input.context()));
            countBody.putObject("reasoning").put("effort", "none");
            if (JSON.writeValueAsBytes(countBody).length > 64000) throw new IllegalArgumentException();
            // Reserve the entire per-call ceiling BEFORE even the token-count request. Unknown usage retains it.
            try (var reservation = budget.reserve(CALL_LIMIT, DiagnosisRunner.Currency.USD)) {
                reservation.dispatched();
                countRequests++;
                var count = post("responses/input_tokens", countBody, deadline);
                if (!"response.input_tokens".equals(count.path("object").asString())) throw new IllegalArgumentException();
                Long inputTokens = token(count.get("input_tokens"));
                if (inputTokens == null || cost(inputTokens, MAX_OUTPUT_TOKENS, 0).compareTo(CALL_LIMIT) > 0)
                    throw new IllegalArgumentException();
                var request = countBody.deepCopy();
                request.put("max_output_tokens", MAX_OUTPUT_TOKENS).put("store", false).put("stream", false)
                        .put("service_tier", "default");
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new InterruptedException();
                generations++;
                var response = post("responses", request, deadline);
                // A different model has no approved price; keep the full reservation and unknown usage/cost.
                if (!MODEL.equals(response.path("model").asString())) throw new IllegalArgumentException();
                var usage = response.path("usage");
                var tokens = new DiagnosisRunner.Tokens(token(usage.get("input_tokens")), token(usage.get("output_tokens")),
                        token(usage.path("input_tokens_details").get("cached_tokens")));
                if (tokens.cachedInput() != null && (tokens.input() == null || tokens.cachedInput() > tokens.input()))
                    throw new IllegalArgumentException();
                if (tokens.input() != null && tokens.output() != null && tokens.cachedInput() != null)
                    reservation.settle(cost(tokens.input(), tokens.output(), tokens.cachedInput()), DiagnosisRunner.Currency.USD);
                else reservation.settle(null, DiagnosisRunner.Currency.USD);
                // Incomplete/refused/unexpected outputs never reach the user, but retain known usage.
                String text = null;
                if ((tokens.input() == null || tokens.input() <= inputTokens)
                        && "completed".equals(response.path("status").asString())
                        && (tokens.output() == null || tokens.output() <= MAX_OUTPUT_TOKENS)) {
                    var output = response.path("output");
                    if (output.isArray() && output.size() == 1) {
                        var message = output.get(0); var content = message.path("content");
                        if ("message".equals(message.path("type").asString()) && "assistant".equals(message.path("role").asString())
                                && "completed".equals(message.path("status").asString()) && content.isArray() && content.size() == 1
                                && "output_text".equals(content.get(0).path("type").asString())
                                && content.get(0).path("text").isString()) text = content.get(0).get("text").asString();
                    }
                }
                if (text == null) halted = true;
                return new DiagnosisRunner.Reply(text, tokens);
            }
        } catch (InterruptedException e) {
            halted = true; Thread.currentThread().interrupt(); throw new InterruptedException("PROVIDER_INTERRUPTED");
        } catch (Exception e) {
            halted = true; throw new IllegalStateException("PROVIDER_FAILURE");
        }
    }
    static BigDecimal cost(long input, long output, long cached) {
        return PRICING.inputPerMillion().multiply(BigDecimal.valueOf(input - cached))
                .add(PRICING.cachedPerMillion().multiply(BigDecimal.valueOf(cached)))
                .add(PRICING.outputPerMillion().multiply(BigDecimal.valueOf(output))).movePointLeft(6);
    }
    private static Long token(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0 || value.asLong() > 1_000_000_000L)
            throw new IllegalArgumentException();
        return value.asLong();
    }
    private JsonNode post(String path, ObjectNode payload, long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException();
        var request = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofNanos(remaining))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(payload))).build();
        var future = client.sendAsync(request, info -> new LimitedBody());
        try {
            var response = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) throw new IllegalStateException();
            return JSON.readTree(response.body());
        } finally { if (!future.isDone()) future.cancel(true); }
    }
    /** Bounds memory while the deadline above covers headers AND a stalled response body. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int received;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > 64000 - received) {
                    subscription.cancel(); delegate.onError(new IllegalStateException("RESPONSE_TOO_LARGE")); return;
                }
                received += buffer.remaining();
            }
            delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
    @Override public String toString() { return "OpenAiInference[credentials redacted]"; }
    @Override public void close() { client.shutdownNow(); }
}
