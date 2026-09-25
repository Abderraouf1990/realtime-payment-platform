package com.aayadi.payment.e2e;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PaymentFlowIT {
    private static final String TOPIC = "transactions.received";
    private static final String GROUP = "payment-e2e";
    private static final Path LOGS = Path.of("target", "failsafe-reports").toAbsolutePath();

    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private KafkaContainer kafka;
    private PostgreSQLContainer postgres;
    private Admin admin;
    private RunningApp processor;
    private HttpClient http;
    private int port;
    private String scenario;
    private TraceCollector traces;

    @BeforeEach
    void startEnvironment(TestInfo test) throws Exception {
        scenario = test.getTestMethod().orElseThrow().getName();
        Files.createDirectories(LOGS.resolve(scenario));
        traces = manage(new TraceCollector(LOGS.resolve(scenario).resolve("spans.json")));
        // Register before starting: @AfterEach also runs when this setup fails partway through.
        kafka = manage(new KafkaContainer("apache/kafka:4.1.1"));
        postgres = manage(new PostgreSQLContainer("postgres:17.6"));
        kafka.start();
        postgres.start();
        admin = manage(Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers())));
        admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1),
                new NewTopic("transactions.rejected", 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
        var api = manage(launch("transaction-api", scenario + "/api.log", kafka, List.of("--server.port=0")));
        processor = manage(launchProcessor(scenario + "/processor.log", kafka, postgres));
        port = api.httpPort();
        processor.awaitLog("Started TransactionProcessorApplication");
        http = manage(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        awaitHealth(processor, 200, "RUNNING");
    }

    @AfterEach
    void stopEnvironment() throws Exception {
        Throwable failure = null;
        while (!resources.isEmpty()) {
            try {
                resources.pop().close();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    private <T extends AutoCloseable> T manage(T resource) {
        resources.push(resource);
        return resource;
    }

    @Test
    void validHttpCreatesOneLedgerRowIT() throws Exception {
        assertLag(processor, 0);
        assertThat(progressMetric(processor, "progress.age")).isEqualTo(-1);
        insertValidTransaction();
        assertLag(processor, 0);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(progressMetric(processor, "progress.age")).isGreaterThanOrEqualTo(0));
        // A healthy idle listener stays at zero lag even as the last progress ages.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(progressMetric(processor, "progress.age")).isGreaterThan(1));
        assertLag(processor, 0);
        awaitHealth(processor, 200, "RUNNING");
        assertThat(total(postgres)).isEqualTo(1);
        assertThat(rejectionCount("TX-E2E")).isZero();
        for (var endpoint : List.of("env", "configprops", "beans")) {
            assertThat(healthRequest(processor, "/actuator/" + endpoint).statusCode()).isEqualTo(404);
        }
        assertAttempts(processor, 1, 0, 0, 0);
        assertProcessingLog(processor, "TX-E2E", "CORR-E2E", "accepted", List.of());
    }

    @Test
    void identicalHttpRetryKeepsOneLedgerRowIT() throws Exception {
        String original = insertValidTransaction();
        post(http, port, "TX-E2E", body("TX-E2E", "CORR-E2E", "42.00", "EUR"));
        awaitOffset(admin, 2);
        assertThat(count(postgres, "TX-E2E")).isEqualTo(1);
        assertThat(total(postgres)).isEqualTo(1);
        assertThat(row(postgres, "TX-E2E")).isEqualTo(original);
        processor.awaitLog("transactionId=TX-E2E outcome=DUPLICATE");
        assertThat(rejectionCount("TX-E2E")).isZero();
        assertAttempts(processor, 1, 1, 0, 0);
        assertProcessingLog(processor, "TX-E2E", "CORR-E2E", "duplicate", List.of());
    }

    @Test
    void changedPayloadIsConflictWithoutMutationIT() throws Exception {
        String original = insertValidTransaction();
        post(http, port, "TX-E2E", body("TX-E2E", "CORR-CONFLICT", "43.00", "EUR"));
        awaitOffset(admin, 2);
        assertThat(row(postgres, "TX-E2E")).isEqualTo(original);
        assertThat(total(postgres)).isEqualTo(1);
        processor.awaitLog("transactionId=TX-E2E correlationId=CORR-CONFLICT reason=PAYLOAD_CONFLICT");
        assertThat(rejectionCount("TX-E2E")).isEqualTo(1);
        assertAttempts(processor, 1, 0, 1, 0);
        assertProcessingLog(processor, "TX-E2E", "CORR-CONFLICT", "rejected", List.of("PAYLOAD_CONFLICT"));
    }

    @Test
    void negativeAmountAndNonEuroCurrencyAreRejectedIT() throws Exception {
        post(http, port, "TX-NEGATIVE", body("TX-NEGATIVE", "CORR-NEGATIVE", "-1.00", "EUR"));
        awaitOffset(admin, 1);
        assertThat(count(postgres, "TX-NEGATIVE")).isZero();
        processor.awaitLog("transactionId=TX-NEGATIVE reasons=[AMOUNT_NOT_POSITIVE]");
        post(http, port, "TX-USD", body("TX-USD", "CORR-USD", "10.00", "USD"));
        awaitOffset(admin, 2);
        assertThat(count(postgres, "TX-USD")).isZero();
        assertThat(total(postgres)).isZero();
        processor.awaitLog("transactionId=TX-USD reasons=[CURRENCY_NOT_EUR]");
        assertThat(rejectionCount("TX-NEGATIVE")).isEqualTo(1);
        assertThat(rejectionCount("TX-USD")).isEqualTo(1);
        assertAttempts(processor, 0, 0, 2, 0);
        assertProcessingLog(processor, "TX-NEGATIVE", "CORR-NEGATIVE", "rejected", List.of("AMOUNT_NOT_POSITIVE"));
        assertProcessingLog(processor, "TX-USD", "CORR-USD", "rejected", List.of("CURRENCY_NOT_EUR"));
    }

    @Test
    void databaseOutageLeavesOffsetUncommittedUntilManualRestartAndReplayIT() throws Exception {
        String original = insertValidTransaction();
        // Export failure is independent of both business success and database failure.
        traces.unavailable(true);
        // Stop the server, preserving the container and its data for recovery.
        postgres.getDockerClient().stopContainerCmd(postgres.getContainerId()).withTimeout(1).exec();
        post(http, port, "TX-OUTAGE", body("TX-OUTAGE", "CORR-OUTAGE", "5.00", "EUR"));
        processor.awaitLog("Ledger processing failed correlationId=CORR-OUTAGE");
        processor.awaitLog("Consumer stopped");
        awaitHealth(processor, 503, "STOPPED");
        assertThat(committed(admin)).isEqualTo(1);

        assertLag(processor, 1);
        post(http, port, "TX-BACKLOG", body("TX-BACKLOG", "CORR-BACKLOG", "7.00", "EUR"));
        assertLag(processor, 2);
        assertThat(committed(admin)).isEqualTo(1);
        assertAttempts(processor, 1, 0, 0, 1);
        assertProcessingLog(processor, "TX-OUTAGE", "CORR-OUTAGE", "technical_failure", List.of("PROCESSING_FAILURE"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(traces.failedRequests()).isPositive());

        postgres.getDockerClient().startContainerCmd(postgres.getContainerId()).exec();
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                assertThat(count(postgres, "TX-OUTAGE")).isZero());
        // Database recovery alone cannot restart the stopped listener.
        assertThat(committed(admin)).isEqualTo(1);
        awaitHealth(processor, 503, "STOPPED");
        processor.close();
        var restarted = manage(launchProcessor(scenario + "/processor-restarted.log", kafka, postgres));
        restarted.awaitLog("Started TransactionProcessorApplication");
        awaitHealth(restarted, 200, "RUNNING");
        awaitOffset(admin, 3);
        assertThat(count(postgres, "TX-OUTAGE")).isEqualTo(1);
        assertThat(count(postgres, "TX-BACKLOG")).isEqualTo(1);
        assertThat(row(postgres, "TX-E2E")).isEqualTo(original);
        assertThat(total(postgres)).isEqualTo(3);
        assertLag(restarted, 0);
        // Counters belong to this JVM, not to the durable ledger or previous process.
        assertAttempts(restarted, 2, 0, 0, 0);
        assertProcessingLog(restarted, "TX-OUTAGE", "CORR-OUTAGE", "accepted", List.of());
        var failed = processingLog(processor, "TX-OUTAGE", "technical_failure");
        var replay = processingLog(restarted, "TX-OUTAGE", "accepted");
        assertThat(replay.get("traceId").asString()).isEqualTo(failed.get("traceId").asString());
        assertThat(replay.get("spanId").asString()).isNotEqualTo(failed.get("spanId").asString());
        // Successful replay was acknowledged even while the trace endpoint still returns 503.
        traces.unavailable(false);
    }

    @Test
    void brokerOutageMakesLagUnknownWithoutBlockingManagementIT() throws Exception {
        insertValidTransaction();
        assertLag(processor, 0);
        kafka.getDockerClient().stopContainerCmd(kafka.getContainerId()).withTimeout(1).exec();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(progressMetric(processor, "observation.available")).isZero();
            assertThat(progressMetric(processor, "lag")).isEqualTo(-1);
            assertThat(progressMetric(processor, "observation.age")).isGreaterThan(1);
            assertThat(healthRequest(processor, "/actuator/health/liveness").statusCode()).isEqualTo(200);
        });
        assertThat(count(postgres, "TX-E2E")).isEqualTo(1);
        assertAttempts(processor, 1, 0, 0, 0);
    }

    private void assertLag(RunningApp app, double expected) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(progressMetric(app, "observation.available")).isEqualTo(1);
            assertThat(progressMetric(app, "lag")).isEqualTo(expected);
            assertThat(progressMetric(app, "observation.age")).isLessThan(10);
        });
    }

    private double progressMetric(RunningApp app, String suffix) throws Exception {
        var response = healthRequest(app, "/actuator/metrics/payments.consumer." + suffix);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var json = JsonMapper.builder().build().readTree(response.body());
        assertThat(json.get("availableTags").size()).isZero();
        return json.get("measurements").get(0).get("value").asDouble();
    }

    @Test
    void tracesLinkHttpKafkaAndRejectionAndIsolateUntracedEventsIT() throws Exception {
        String traceId = "1234567890abcdef1234567890abcdef";
        String upstream = "1234567890abcdef";
        post(http, port, "TX-TRACE", body("TX-TRACE", "CORR-SHARED", "-1.00", "EUR"),
                "00-" + traceId + "-" + upstream + "-01");
        awaitOffset(admin, 1);
        assertProcessingLog(processor, "TX-TRACE", "CORR-SHARED", "rejected", List.of("AMOUNT_NOT_POSITIVE"));
        var log = processingLog(processor, "TX-TRACE", "rejected");
        assertThat(log.get("traceId").asString()).isEqualTo(traceId);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var trace = traces.spans().stream().filter(s -> s.path("traceId").asString().equals(traceId)).toList();
            var server = oneSpan(trace, "SERVER", "transaction-api");
            var producer = oneSpan(trace, "PRODUCER", "transaction-api");
            var consumer = oneSpan(trace, "CONSUMER", "transaction-processor");
            var rejection = oneSpan(trace, "PRODUCER", "transaction-processor");
            assertThat(server.path("parentId").asString()).isEqualTo(upstream);
            assertThat(producer.path("parentId").asString()).isEqualTo(server.path("id").asString());
            assertThat(consumer.path("parentId").asString()).isEqualTo(producer.path("id").asString());
            assertThat(rejection.path("parentId").asString()).isEqualTo(consumer.path("id").asString());
            assertThat(consumer.path("id").asString()).isEqualTo(log.get("spanId").asString());
            assertThat(trace.toString()).doesNotContain("ACC-E2E", "accountId", "CORR-SHARED", "TX-TRACE", "password");
        });

        // Same partition/listener thread, but no transport tracing and the same business correlation.
        publishWithoutContext("TX-NO-TRACE", false);
        awaitOffset(admin, 2);
        assertProcessingLog(processor, "TX-NO-TRACE", "CORR-SHARED", "accepted", List.of());
        var fresh = processingLog(processor, "TX-NO-TRACE", "accepted");
        assertThat(fresh.get("traceId").asString()).hasSize(32).isNotEqualTo(traceId);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var roots = traces.spans().stream().filter(s -> s.path("id").asString()
                    .equals(fresh.get("spanId").asString())).toList();
            assertThat(roots).hasSize(1);
            assertThat(roots.getFirst().has("parentId")).isFalse();
        });
        publishWithoutContext("TX-BAD-TRACE", true);
        awaitOffset(admin, 3);
        assertProcessingLog(processor, "TX-BAD-TRACE", "CORR-SHARED", "accepted", List.of());
        assertThat(processingLog(processor, "TX-BAD-TRACE", "accepted").get("traceId").asString())
                .isNotEqualTo(traceId).isNotEqualTo(fresh.get("traceId").asString());

        // A repeated HTTP request gets a new trace despite identical business identity.
        post(http, port, "TX-NO-TRACE", body("TX-NO-TRACE", "CORR-SHARED", "42.00", "EUR"));
        awaitOffset(admin, 4);
        assertProcessingLog(processor, "TX-NO-TRACE", "CORR-SHARED", "duplicate", List.of());
        assertThat(processingLog(processor, "TX-NO-TRACE", "duplicate").get("traceId").asString())
                .isNotEqualTo(traceId).isNotEqualTo(fresh.get("traceId").asString());
        assertThat(total(postgres)).isEqualTo(2);
        assertThat(rejectionCount("TX-TRACE")).isEqualTo(1);
        assertAttempts(processor, 2, 1, 1, 0);
    }

    private static tools.jackson.databind.JsonNode oneSpan(List<tools.jackson.databind.JsonNode> trace,
                                                          String kind, String service) {
        var matches = trace.stream().filter(s -> s.path("kind").asString().equals(kind)
                && s.path("localEndpoint").path("serviceName").asString().equals(service)).toList();
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }

    private tools.jackson.databind.JsonNode processingLog(RunningApp app, String id, String outcome) throws Exception {
        var mapper = JsonMapper.builder().build();
        return Files.readAllLines(app.log()).stream().filter(l -> l.startsWith("{"))
                .map(mapper::readTree).filter(j -> j.path("event").asString().equals("payment.processing")
                        && j.path("transactionId").asString().equals(id) && j.path("outcome").asString().equals(outcome))
                .findFirst().orElseThrow();
    }

    private void publishWithoutContext(String id, boolean malformed) throws Exception {
        var json = JsonMapper.builder().build().readTree(body(id, "CORR-SHARED", "42.00", "EUR"));
        var event = (tools.jackson.databind.node.ObjectNode) json;
        event.put("schemaVersion", 1);
        event.put("receivedAt", "2026-09-25T00:00:00Z");
        try (var producer = new org.apache.kafka.clients.producer.KafkaProducer<String, String>(
                Map.of("bootstrap.servers", kafka.getBootstrapServers()),
                new org.apache.kafka.common.serialization.StringSerializer(),
                new org.apache.kafka.common.serialization.StringSerializer())) {
            var record = new org.apache.kafka.clients.producer.ProducerRecord<String, String>(TOPIC, id, event.toString());
            if (malformed) record.headers().add("traceparent", "invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            producer.send(record).get(10, TimeUnit.SECONDS);
        }
    }

    private void assertAttempts(RunningApp app, int accepted, int duplicate, int rejected, int failed) throws Exception {
        var expected = Map.of("accepted", accepted, "duplicate", duplicate, "rejected", rejected, "technical_failure", failed);
        var mapper = JsonMapper.builder().build();
        var base = healthRequest(app, "/actuator/metrics/payments.processing.attempts");
        assertThat(base.statusCode()).as(base.body()).isEqualTo(200);
        var tags = mapper.readTree(base.body()).get("availableTags");
        assertThat(tags.size()).isEqualTo(1);
        assertThat(tags.get(0).get("tag").asString()).isEqualTo("outcome");
        var values = new ArrayList<String>();
        for (var value : tags.get(0).get("values")) values.add(value.asString());
        assertThat(values).containsExactlyInAnyOrderElementsOf(expected.keySet());
        for (var entry : expected.entrySet()) {
            var response = healthRequest(app, "/actuator/metrics/payments.processing.attempts?tag=outcome:" + entry.getKey());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(response.body()).get("measurements").get(0).get("value").asDouble())
                    .as(entry.getKey()).isEqualTo(entry.getValue().doubleValue());
        }
    }

    private void assertProcessingLog(RunningApp app, String id, String correlation, String outcome, List<String> reasons) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var mapper = JsonMapper.builder().build();
            var matches = new ArrayList<tools.jackson.databind.JsonNode>();
            for (var line : Files.readAllLines(app.log())) {
                if (!line.startsWith("{")) continue; // JVM diagnostics need not be JSON.
                var json = mapper.readTree(line);
                if (json.has("event") && json.get("event").asString().equals("payment.processing")
                        && json.get("transactionId").asString().equals(id) && json.get("outcome").asString().equals(outcome)) {
                    assertThat(line).doesNotContain("ACC-E2E", "accountId", "jdbc:", "password", "stack_trace");
                    matches.add(json);
                }
            }
            assertThat(matches).hasSize(1);
            var json = matches.getFirst();
            assertThat(json.get("correlationId").asString()).isEqualTo(correlation);
            var actualReasons = new ArrayList<String>();
            for (var reason : json.get("reasonCodes")) actualReasons.add(reason.asString());
            assertThat(actualReasons).isEqualTo(reasons);
            assertThat(json.get("partition").asInt()).isZero();
            assertThat(json.get("offset").asLong()).isGreaterThanOrEqualTo(0);
        });
    }

    private void awaitHealth(RunningApp app, int expectedCode, String state) throws Exception {
        int healthPort = app.httpPort();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(app.process().isAlive()).isTrue();
            var listener = healthRequest(healthPort, "/actuator/health/listener");
            assertThat(listener.statusCode()).as(listener.body()).isEqualTo(expectedCode);
            assertThat(listener.body()).contains("\"state\":\"" + state + "\"")
                    .doesNotContain("TX-", "CORR-", "ACC-", "jdbc:", "password", "exception");
            assertThat(healthRequest(healthPort, "/actuator/health/readiness").statusCode()).isEqualTo(expectedCode);
            // A stopped consumer must not trigger an automatic JVM restart via liveness.
            var liveness = healthRequest(healthPort, "/actuator/health/liveness");
            assertThat(liveness.statusCode()).isEqualTo(200);
            assertThat(liveness.body()).isEqualTo("{\"status\":\"UP\"}");
        });
    }

    private HttpResponse<String> healthRequest(RunningApp app, String path) throws Exception {
        return healthRequest(app.httpPort(), path);
    }

    private HttpResponse<String> healthRequest(int healthPort, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + healthPort + path))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String insertValidTransaction() throws Exception {
        post(http, port, "TX-E2E", body("TX-E2E", "CORR-E2E", "42.00", "EUR"));
        awaitOffset(admin, 1);
        assertThat(count(postgres, "TX-E2E")).isEqualTo(1);
        return row(postgres, "TX-E2E");
    }

    private RunningApp launchProcessor(String log, KafkaContainer kafka, PostgreSQLContainer postgres) throws Exception {
        return launch("transaction-processor", log, kafka, List.of(
                "--server.port=0",
                "--payments.monitoring.interval=500",
                "--spring.datasource.url=" + jdbcUrl(postgres),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.datasource.hikari.connection-timeout=3000",
                "--spring.datasource.hikari.validation-timeout=1000",
                "--spring.kafka.consumer.group-id=" + GROUP));
    }

    private RunningApp launch(String module, String log, KafkaContainer kafka, List<String> extra) throws Exception {
        var root = Path.of(System.getProperty("repository.root"));
        var jar = root.resolve(module).resolve("target")
                .resolve(module + "-" + System.getProperty("application.version") + ".jar");
        assertThat(jar).isRegularFile();
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = new ArrayList<>(List.of(java, "-jar", jar.toString(),
                "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                "--management.tracing.sampling.probability=1.0",
                "--management.tracing.export.zipkin.enabled=true",
                "--management.tracing.export.zipkin.endpoint=" + traces.endpoint(),
                "--payments.kafka.received-topic=" + TOPIC));
        command.addAll(extra);
        var output = LOGS.resolve(log);
        var process = new ProcessBuilder(command).directory(root.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new RunningApp(process, output);
    }

    private static void post(HttpClient http, int port, String id, String body) throws Exception {
        post(http, port, id, body, null);
    }

    private static void post(HttpClient http, int port, String id, String body, String traceparent) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transactions"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header("Idempotency-Key", id).POST(HttpRequest.BodyPublishers.ofString(body));
        if (traceparent != null) builder.header("traceparent", traceparent);
        var request = builder.build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        assertThat(response.body()).contains(id);
    }

    private static String body(String id, String correlation, String amount, String currency) {
        return """
                {"transactionId":"%s","correlationId":"%s","accountId":"ACC-E2E",
                 "amount":%s,"currency":"%s","type":"TRANSFER"}
                """.formatted(id, correlation, amount, currency);
    }

    private static void awaitOffset(Admin admin, long expected) {
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(committed(admin)).isEqualTo(expected));
    }

    private static long committed(Admin admin) throws Exception {
        var offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
                .get(new TopicPartition(TOPIC, 0));
        return offset == null ? -1 : offset.offset();
    }

    private static int count(PostgreSQLContainer postgres, String id) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(postgres), postgres.getUsername(), postgres.getPassword());
             var query = connection.prepareStatement("SELECT count(*) FROM ledger_transactions WHERE transaction_id = ?")) {
            query.setString(1, id);
            try (var result = query.executeQuery()) { result.next(); return result.getInt(1); }
        }
    }

    private int rejectionCount(String id) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(postgres), postgres.getUsername(), postgres.getPassword());
             var query = connection.prepareStatement("SELECT count(*) FROM transaction_rejections WHERE transaction_id = ?")) {
            query.setString(1, id);
            try (var result = query.executeQuery()) { result.next(); return result.getInt(1); }
        }
    }

    private static String jdbcUrl(PostgreSQLContainer postgres) {
        // Docker can assign a different host port on restart; do not use Testcontainers' cached mapping.
        var info = postgres.getDockerClient().inspectContainerCmd(postgres.getContainerId()).exec();
        var binding = info.getNetworkSettings().getPorts().getBindings()
                .get(com.github.dockerjava.api.model.ExposedPort.tcp(5432))[0];
        return "jdbc:postgresql://" + postgres.getHost() + ":" + binding.getHostPortSpec()
                + "/" + postgres.getDatabaseName() + "?connectTimeout=2&socketTimeout=3";
    }

    private static int total(PostgreSQLContainer postgres) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(postgres), postgres.getUsername(), postgres.getPassword());
             var query = connection.createStatement(); var result = query.executeQuery("SELECT count(*) FROM ledger_transactions")) {
            result.next(); return result.getInt(1);
        }
    }

    private static String row(PostgreSQLContainer postgres, String id) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(postgres), postgres.getUsername(), postgres.getPassword());
             var query = connection.prepareStatement("SELECT row_to_json(t)::text FROM ledger_transactions t WHERE transaction_id = ?")) {
            query.setString(1, id);
            try (var result = query.executeQuery()) { assertThat(result.next()).isTrue(); return result.getString(1); }
        }
    }

    private record RunningApp(Process process, Path log) implements AutoCloseable {
        void awaitLog(String text) {
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                assertThat(Files.readString(log)).contains(text);
            });
        }

        int httpPort() throws Exception {
            awaitLog("Tomcat started on port");
            var match = Pattern.compile("Tomcat started on port (\\d+)").matcher(Files.readString(log));
            assertThat(match.find()).as("HTTP port in %s", log).isTrue();
            return Integer.parseInt(match.group(1));
        }

        @Override public void close() throws Exception {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
    }
}
