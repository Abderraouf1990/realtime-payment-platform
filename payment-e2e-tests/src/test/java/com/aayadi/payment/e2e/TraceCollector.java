package com.aayadi.payment.e2e;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/** Local Zipkin protocol sink; applications still use their real asynchronous exporter. */
final class TraceCollector implements AutoCloseable {
    private final HttpServer server;
    private final List<JsonNode> spans = new CopyOnWriteArrayList<>();
    private final AtomicInteger failures = new AtomicInteger();
    private final Path evidence;
    private volatile boolean unavailable;

    TraceCollector(Path evidence) throws IOException {
        this.evidence = evidence;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/spans", exchange -> {
            try (exchange) {
                if (unavailable) {
                    failures.incrementAndGet();
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                var input = exchange.getRequestBody();
                if ("gzip".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
                    input = new GZIPInputStream(input);
                }
                for (var span : JsonMapper.builder().build().readTree(input.readAllBytes())) spans.add(span);
                exchange.sendResponseHeaders(202, -1);
            }
        });
        server.start();
    }

    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2/spans"; }
    List<JsonNode> spans() { return List.copyOf(spans); }
    void unavailable(boolean value) { unavailable = value; }
    int failedRequests() { return failures.get(); }

    @Override public void close() throws IOException {
        server.stop(0);
        Files.writeString(evidence, JsonMapper.builder().build().writeValueAsString(spans));
    }
}
