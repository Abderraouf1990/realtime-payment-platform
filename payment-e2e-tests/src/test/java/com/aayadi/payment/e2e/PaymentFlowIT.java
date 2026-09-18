package com.aayadi.payment.e2e;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
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

    @Test
    void httpToLedgerDuplicatesConflictsRejectionsAndDatabaseOutage() throws Exception {
        Files.createDirectories(LOGS);
        try (var kafka = new KafkaContainer("apache/kafka-native:4.1.1");
             var postgres = new PostgreSQLContainer("postgres:17.6")) {
            kafka.start();
            postgres.start();
            try (var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
                admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
                try (var api = launch("transaction-api", "api.log", kafka, List.of("--server.port=0"));
                     var processor = launchProcessor("processor.log", kafka, postgres)) {
                    int port = api.httpPort();
                    processor.awaitLog("Started TransactionProcessorApplication");
                    try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                        String valid = body("TX-E2E", "CORR-E2E", "42.00", "EUR");
                        post(http, port, "TX-E2E", valid);
                        awaitOffset(admin, 1);
                        assertThat(count(postgres, "TX-E2E")).isEqualTo(1);
                        String original = row(postgres, "TX-E2E");

                        post(http, port, "TX-E2E", valid);
                        awaitOffset(admin, 2);
                        assertThat(count(postgres, "TX-E2E")).isEqualTo(1);
                        assertThat(row(postgres, "TX-E2E")).isEqualTo(original);
                        processor.awaitLog("transactionId=TX-E2E outcome=DUPLICATE");

                        post(http, port, "TX-E2E", body("TX-E2E", "CORR-CONFLICT", "43.00", "EUR"));
                        awaitOffset(admin, 3);
                        assertThat(row(postgres, "TX-E2E")).isEqualTo(original);
                        processor.awaitLog("transactionId=TX-E2E correlationId=CORR-CONFLICT reason=PAYLOAD_CONFLICT");

                        post(http, port, "TX-NEGATIVE", body("TX-NEGATIVE", "CORR-NEGATIVE", "-1.00", "EUR"));
                        awaitOffset(admin, 4);
                        assertThat(count(postgres, "TX-NEGATIVE")).isZero();
                        processor.awaitLog("transactionId=TX-NEGATIVE reasons=[AMOUNT_NOT_POSITIVE]");
                        post(http, port, "TX-USD", body("TX-USD", "CORR-USD", "10.00", "USD"));
                        awaitOffset(admin, 5);
                        assertThat(count(postgres, "TX-USD")).isZero();
                        processor.awaitLog("transactionId=TX-USD reasons=[CURRENCY_NOT_EUR]");

                        // Stop the server, preserving the container and its data for recovery.
                        postgres.getDockerClient().stopContainerCmd(postgres.getContainerId()).withTimeout(1).exec();
                        post(http, port, "TX-OUTAGE", body("TX-OUTAGE", "CORR-OUTAGE", "5.00", "EUR"));
                        processor.awaitLog("Ledger processing failed correlationId=CORR-OUTAGE");
                        processor.awaitLog("Consumer stopped");
                        assertThat(committed(admin)).isEqualTo(5);

                        postgres.getDockerClient().startContainerCmd(postgres.getContainerId()).exec();
                        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                                assertThat(count(postgres, "TX-OUTAGE")).isZero());
                        // Database recovery alone cannot restart the stopped listener.
                        assertThat(committed(admin)).isEqualTo(5);
                        processor.close();
                        try (var restarted = launchProcessor("processor-restarted.log", kafka, postgres)) {
                            restarted.awaitLog("Started TransactionProcessorApplication");
                            awaitOffset(admin, 6);
                            assertThat(count(postgres, "TX-OUTAGE")).isEqualTo(1);
                            assertThat(row(postgres, "TX-E2E")).isEqualTo(original);
                            assertThat(total(postgres)).isEqualTo(2);
                        }
                    }
                }
            }
        }
    }

    private static RunningApp launchProcessor(String log, KafkaContainer kafka, PostgreSQLContainer postgres) throws Exception {
        return launch("transaction-processor", log, kafka, List.of(
                "--spring.datasource.url=" + jdbcUrl(postgres),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.datasource.hikari.connection-timeout=3000",
                "--spring.datasource.hikari.validation-timeout=1000",
                "--spring.kafka.consumer.group-id=" + GROUP));
    }

    private static RunningApp launch(String module, String log, KafkaContainer kafka, List<String> extra) throws Exception {
        var root = Path.of(System.getProperty("repository.root"));
        var jar = root.resolve(module).resolve("target")
                .resolve(module + "-" + System.getProperty("application.version") + ".jar");
        assertThat(jar).isRegularFile();
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = new ArrayList<>(List.of(java, "-jar", jar.toString(),
                "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                "--payments.kafka.received-topic=" + TOPIC));
        command.addAll(extra);
        var output = LOGS.resolve(log);
        var process = new ProcessBuilder(command).directory(root.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new RunningApp(process, output);
    }

    private static void post(HttpClient http, int port, String id, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/transactions"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header("Idempotency-Key", id).POST(HttpRequest.BodyPublishers.ofString(body)).build();
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
                // A non-web processor can exit after its last consumer stops on a technical failure.
                assertThat(Files.readString(log)).contains(text);
            });
        }

        int httpPort() throws Exception {
            awaitLog("Started TransactionApiApplication");
            var match = Pattern.compile("Tomcat started on port (\\d+)").matcher(Files.readString(log));
            assertThat(match.find()).as("HTTP port in %s", log).isTrue();
            return Integer.parseInt(match.group(1));
        }

        @Override public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
