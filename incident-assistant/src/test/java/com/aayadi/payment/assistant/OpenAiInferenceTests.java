package com.aayadi.payment.assistant;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class OpenAiInferenceTests {
    private static final Path ROOT = Path.of(System.getProperty("repository.root"));
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private HttpServer server;
    private ExecutorService workers;
    private URI endpoint;
    private final List<ObjectNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger counts = new AtomicInteger();
    private final AtomicInteger generations = new AtomicInteger();
    private volatile String countResponse = "{\"object\":\"response.input_tokens\",\"input_tokens\":1000}";
    private volatile String response;
    private volatile int status = 200;
    private volatile long bodyDelay;
    private volatile String authorization;
    private final CountDownLatch bodyStarted = new CountDownLatch(1);

    @BeforeEach void start() throws Exception {
        response = envelope().toString();
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
        server.createContext("/v1/", exchange -> {
            try {
                authorization = exchange.getRequestHeaders().getFirst("Authorization");
                requests.add((ObjectNode) JSON.readTree(exchange.getRequestBody().readAllBytes()));
                boolean count = exchange.getRequestURI().getPath().endsWith("input_tokens");
                if (count) counts.incrementAndGet(); else generations.incrementAndGet();
                byte[] bytes = (count ? countResponse : response).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Location", endpoint + "redirected");
                exchange.sendResponseHeaders(count ? 200 : status, bytes.length);
                if (!count) { bodyStarted.countDown(); Thread.sleep(bodyDelay); }
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { /* client cancellation is expected in timeout/size tests */ }
            finally { exchange.close(); }
        });
        server.start(); endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
    }
    @AfterEach void stop() { server.stop(0); workers.shutdownNow(); }
    private static ObjectNode envelope() {
        var node = JSON.createObjectNode().put("model", OpenAiInference.MODEL).put("status", "completed");
        node.putObject("usage").put("input_tokens", 1000).put("output_tokens", 100)
                .putObject("input_tokens_details").put("cached_tokens", 200);
        node.putArray("output").addObject().put("type", "message").put("role", "assistant").put("status", "completed")
                .putArray("content").addObject().put("type", "output_text")
                .put("text", ResponsePolicy.proposal("unknown", List.of(), List.of()).toString());
        return node;
    }
    private static EvaluationBudget budget(int calls) {
        return new EvaluationBudget(new BigDecimal("0.05"), new BigDecimal("2.00"), calls, DiagnosisRunner.Currency.USD);
    }
    private OpenAiInference adapter(EvaluationBudget budget) throws Exception { return new OpenAiInference("fake-key-canary", ROOT, budget, endpoint); }
    private static DiagnosisRunner.Input input() {
        return new DiagnosisRunner.Input("restricted-output-v1", new ContextBuilder.Bundle("1", Instant.parse("2026-10-07T18:00:00Z"),
                List.of("lag"), List.of(), List.of()));
    }
    @Test void countsExactPromptBeforeGenerationAndNormalizesUsage() throws Exception {
        var budget = budget(39);
        try (var adapter = adapter(budget)) {
            var reply = adapter.infer(input(), Duration.ofSeconds(3));
            assertThat(reply.tokens()).isEqualTo(new DiagnosisRunner.Tokens(1000L, 100L, 200L));
            assertThat(reply.json()).contains("unknown");
            assertThat(authorization).isEqualTo("Bearer fake-key-canary");
            assertThat(adapter.toString()).doesNotContain("fake-key-canary");
            assertThat(counts).hasValue(1); assertThat(generations).hasValue(1);
            var generate = requests.get(1).deepCopy();
            assertThat(generate.remove("store").asBoolean()).isFalse();
            assertThat(generate.remove("stream").asBoolean()).isFalse();
            assertThat(generate.remove("max_output_tokens").asInt()).isEqualTo(2000);
            assertThat(generate.remove("service_tier").asString()).isEqualTo("default");
            assertThat(generate).isEqualTo(requests.get(0));
            assertThat(generate.toString()).doesNotContain("fake-key-canary", "tools", "expected", "mustMention");
            assertThat(budget.snapshot().committedOrReserved()).isEqualByComparingTo("0.001065");
        }
    }
    @Test void countOverBudgetPreventsGenerationAndHaltsWithoutRetry() throws Exception {
        countResponse = "{\"object\":\"response.input_tokens\",\"input_tokens\":100000}";
        var budget = budget(39);
        try (var adapter = adapter(budget)) {
            assertThatThrownBy(() -> adapter.infer(input(), Duration.ofSeconds(3))).hasMessage("PROVIDER_FAILURE").hasNoCause();
            assertThatThrownBy(() -> adapter.infer(input(), Duration.ofSeconds(3))).hasMessage("PROVIDER_HALTED");
            assertThat(counts).hasValue(1); assertThat(generations).hasValue(0);
            assertThat(budget.snapshot().committedOrReserved()).isEqualByComparingTo("0.05");
        }
    }
    @Test void exhaustedAttemptBudgetPreventsEvenCounting() throws Exception {
        try (var adapter = adapter(budget(1))) {
            adapter.infer(input(), Duration.ofSeconds(3));
            assertThatThrownBy(() -> adapter.infer(input(), Duration.ofSeconds(3))).hasMessage("PROVIDER_FAILURE");
            assertThat(counts).hasValue(1); assertThat(generations).hasValue(1);
        }
    }
    @Test void missingUsageStaysUnknownAndRetainsFullReservation() throws Exception {
        var node = envelope(); node.remove("usage"); response = node.toString();
        var budget = budget(39);
        try (var adapter = adapter(budget)) {
            assertThat(adapter.infer(input(), Duration.ofSeconds(3)).tokens()).isEqualTo(new DiagnosisRunner.Tokens(null, null, null));
            assertThat(budget.snapshot().committedOrReserved()).isEqualByComparingTo("0.05");
        }
    }
    @Test void incompleteOutputIsNotReturnedButKnownUsageIsRetained() throws Exception {
        response = envelope().put("status", "incomplete").toString();
        try (var adapter = adapter(budget(39))) {
            var reply = adapter.infer(input(), Duration.ofSeconds(3));
            assertThat(reply.json()).isNull(); assertThat(reply.tokens().output()).isEqualTo(100L);
            assertThat(adapter.halted()).isTrue();
        }
    }
    @Test void wrongModelCannotBePricedUsingTheApprovedModelRates() throws Exception {
        response = envelope().put("model", "unapproved-model").toString();
        var budget = budget(39);
        try (var adapter = adapter(budget)) {
            assertThatThrownBy(() -> adapter.infer(input(), Duration.ofSeconds(3))).hasMessage("PROVIDER_FAILURE");
            assertThat(budget.snapshot().committedOrReserved()).isEqualByComparingTo("0.05");
            assertThat(adapter.halted()).isTrue();
        }
    }
    @Test void malformedCountsNeverTriggerGeneration() throws Exception {
        for (String value : List.of("null", "-1", "1.5", "\"1000\"")) {
            countResponse = "{\"object\":\"response.input_tokens\",\"input_tokens\":" + value + "}";
            try (var adapter = adapter(budget(39))) {
                assertThatThrownBy(() -> adapter.infer(input(), Duration.ofSeconds(3))).hasMessage("PROVIDER_FAILURE");
            }
        }
        assertThat(counts).hasValue(4); assertThat(generations).hasValue(0);
    }
    @Test void httpFailureAndRedirectCannotLeakProviderBodyOrRetry() throws Exception {
        for (int httpStatus : List.of(429, 500, 302)) {
            status = httpStatus; response = "fake-key-canary secret payload";
            try (var adapter = adapter(budget(39))) {
                assertThatThrownBy(() -> adapter.infer(input(), Duration.ofSeconds(3))).hasMessage("PROVIDER_FAILURE").hasNoCause();
            }
        }
        assertThat(counts).hasValue(3); assertThat(generations).hasValue(3);
    }
    @Test void duplicateJsonAndInvalidTokenTypesFailClosed() throws Exception {
        for (String invalid : List.of("{\"status\":\"completed\",\"status\":\"secret\"}",
                envelope().toString().replace("\"output_tokens\":100", "\"output_tokens\":\"100\""))) {
            response = invalid;
            try (var adapter = adapter(budget(39))) {
                assertThatThrownBy(() -> adapter.infer(input(), Duration.ofSeconds(3))).hasMessage("PROVIDER_FAILURE").hasNoCause();
            }
        }
    }
    @Test void oversizedBodyIsCancelledWithoutReturningItsContents() throws Exception {
        response = "secret-canary".repeat(10000);
        try (var adapter = adapter(budget(39))) {
            assertThatThrownBy(() -> adapter.infer(input(), Duration.ofSeconds(3))).hasMessage("PROVIDER_FAILURE").hasNoCause();
        }
    }
    @Test void deadlineCoversStalledBodyAndRetainsUnknownCost() throws Exception {
        bodyDelay = 4000; var budget = budget(39);
        try (var adapter = adapter(budget)) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> adapter.infer(input(), Duration.ofMillis(700))).hasMessage("PROVIDER_FAILURE");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            assertThat(bodyStarted.getCount()).isZero();
            assertThat(budget.snapshot().committedOrReserved()).isEqualByComparingTo("0.05");
        }
    }
    @Test void interruptionCancelsInFlightExchange() throws Exception {
        bodyDelay = 4000;
        try (var adapter = adapter(budget(39)); var executor = Executors.newSingleThreadExecutor()) {
            var finished = new CountDownLatch(1);
            var future = executor.submit(() -> {
                try { adapter.infer(input(), Duration.ofSeconds(10)); }
                catch (Exception ignored) { }
                finally { finished.countDown(); }
            });
            assertThat(bodyStarted.await(3, TimeUnit.SECONDS)).isTrue(); future.cancel(true);
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue(); assertThat(adapter.halted()).isTrue();
        }
    }
    @Test void runnerRejectsProviderSecretsWhileRetainingKnownUsage() throws Exception {
        var node = envelope(); ((ObjectNode) node.get("output").get(0).get("content").get(0)).put("text", "fake-key-canary");
        response = node.toString();
        var now = Instant.now(); var scope = new ContextBuilder.Scope("incident", "processor");
        var builder = new ContextBuilder(Clock.fixed(now, ZoneOffset.UTC), s -> List.of(), terms -> List.of());
        try (var adapter = adapter(budget(39)); var runner = new DiagnosisRunner(builder,
                new ResponsePolicy(ROOT.resolve("docs/assistant/v1/response.schema.json")), adapter, Duration.ofSeconds(3), OpenAiInference.PRICING)) {
            var result = runner.run(new ContextBuilder.Principal("operator", now.plusSeconds(60), Set.of("incident-reader"), Set.of(scope)),
                    new ContextBuilder.Request(scope, "lag"));
            assertThat(result.status()).isEqualTo("rejected"); assertThat(result.response()).isNull();
            assertThat(result.usage().estimatedCost()).isEqualByComparingTo("0.001065");
            assertThat(JSON.writeValueAsString(result)).doesNotContain("fake-key-canary");
        }
    }
}
