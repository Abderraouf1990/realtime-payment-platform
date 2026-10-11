package com.aayadi.payment.assistant;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.*;

/** Read-only runner; network inference requires an explicitly supplied adapter. */
public final class DiagnosisRunner implements AutoCloseable {
    public record Input(String policyVersion, ContextBuilder.Bundle context) {}
    public record Tokens(Long input, Long output, Long cachedInput) {}
    public enum Currency { USD, EUR }
    public record Pricing(LocalDate asOf, Currency currency, BigDecimal inputPerMillion,
                          BigDecimal outputPerMillion, BigDecimal cachedPerMillion) {
        public Pricing {
            Objects.requireNonNull(asOf); Objects.requireNonNull(currency);
            for (var rate : new BigDecimal[]{inputPerMillion, outputPerMillion, cachedPerMillion})
                if (rate == null || rate.signum() < 0) throw new IllegalArgumentException("INVALID_PRICING");
        }
    }
    public record Reply(String json, Tokens tokens) {
        @Override public String toString() { return "Reply[redacted]"; }
    }
    public record Usage(Tokens tokens, BigDecimal estimatedCost, Currency currency, LocalDate pricingDate) {}
    public record Result(String status, String reason, JsonNode response, Usage usage, long elapsedMillis) {}
    @FunctionalInterface public interface InferencePort {
        Reply infer(Input input, Duration timeout) throws Exception;
    }
    private final ContextBuilder contexts;
    private final ResponsePolicy policy;
    private final InferencePort inference;
    private final Duration timeout;
    private final Pricing pricing;
    private final ThreadPoolExecutor executor;

    public DiagnosisRunner(ContextBuilder contexts, ResponsePolicy policy, InferencePort inference,
                           Duration timeout, Pricing pricing) {
        this.contexts = Objects.requireNonNull(contexts); this.policy = Objects.requireNonNull(policy);
        this.inference = Objects.requireNonNull(inference); this.timeout = Objects.requireNonNull(timeout);
        this.pricing = pricing;
        if (timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("INVALID_TIMEOUT");
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            var thread = new Thread(task, "incident-inference"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public Result run(ContextBuilder.Principal principal, ContextBuilder.Request request) {
        long start = System.nanoTime();
        var context = contexts.build(principal, request);
        if (!context.status().equals("ready"))
            return result(context.status(), context.reason(), null, null, start);
        Future<Reply> future = null;
        Usage usage = null;
        try {
            future = executor.submit(() -> inference.infer(new Input("restricted-output-v1", context.context()), timeout));
            var reply = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            usage = usage(reply.tokens());
            var response = policy.validate(reply.json(), context.context());
            return result("completed", "VALIDATED_RESPONSE", response, usage, start);
        } catch (TimeoutException e) {
            if (future != null) future.cancel(true);
            return result("failed", "INFERENCE_TIMEOUT", null, null, start);
        } catch (InterruptedException e) {
            if (future != null) future.cancel(true);
            Thread.currentThread().interrupt();
            return result("failed", "INTERRUPTED", null, null, start);
        } catch (RejectedExecutionException e) {
            return result("failed", "INFERENCE_UNAVAILABLE", null, null, start);
        } catch (ResponsePolicy.InvalidResponse e) {
            return result("rejected", "INVALID_RESPONSE", null, usage, start);
        } catch (Exception e) {
            return result("failed", "INFERENCE_FAILURE", null, null, start);
        }
    }

    private Usage usage(Tokens tokens) {
        if (tokens == null) return new Usage(null, null, null, null);
        for (Long count : new Long[]{tokens.input(), tokens.output(), tokens.cachedInput()})
            if (count != null && (count < 0 || count > 1_000_000_000L)) throw new IllegalArgumentException();
        if (tokens.cachedInput() != null && (tokens.input() == null || tokens.cachedInput() > tokens.input()))
            throw new IllegalArgumentException();
        if (pricing == null || tokens.input() == null || tokens.output() == null || tokens.cachedInput() == null)
            return new Usage(tokens, null, null, null);
        var cost = pricing.inputPerMillion().multiply(BigDecimal.valueOf(tokens.input() - tokens.cachedInput()))
                .add(pricing.cachedPerMillion().multiply(BigDecimal.valueOf(tokens.cachedInput())))
                .add(pricing.outputPerMillion().multiply(BigDecimal.valueOf(tokens.output())))
                .movePointLeft(6);
        return new Usage(tokens, cost, pricing.currency(), pricing.asOf());
    }
    private static Result result(String status, String reason, JsonNode response, Usage usage, long start) {
        return new Result(status, reason, response, usage, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
    @Override public void close() { executor.shutdownNow(); }
}
