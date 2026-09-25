package com.aayadi.payment.processor.adapter.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Read-only group observation, never the listener's KafkaConsumer or commit path. */
@Component
public class KafkaProgressSource implements AutoCloseable {
    private final Admin admin;
    private final String topic;
    private final String group;
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    public KafkaProgressSource(KafkaAdmin configuration,
                               @Value("${payments.kafka.received-topic}") String topic,
                               @Value("${spring.kafka.consumer.group-id}") String group) {
        var properties = new HashMap<String, Object>(configuration.getConfigurationProperties());
        properties.put(AdminClientConfig.CLIENT_ID_CONFIG, "payment-progress-monitor");
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis());
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) TIMEOUT.toMillis());
        this.admin = Admin.create(properties);
        this.topic = topic;
        this.group = group;
    }

    public Map<Integer, Offsets> read() throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        var description = get(admin.describeTopics(List.of(topic)).allTopicNames(), deadline).get(topic);
        var earliest = new HashMap<TopicPartition, OffsetSpec>();
        var latest = new HashMap<TopicPartition, OffsetSpec>();
        for (var partition : description.partitions()) {
            var key = new TopicPartition(topic, partition.partition());
            earliest.put(key, OffsetSpec.earliest());
            latest.put(key, OffsetSpec.latest());
        }
        // These independent reads are approximate, not an atomic broker snapshot.
        var startsFuture = admin.listOffsets(earliest).all();
        var endsFuture = admin.listOffsets(latest).all();
        var commitsFuture = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata();
        var starts = get(startsFuture, deadline);
        var ends = get(endsFuture, deadline);
        var commits = get(commitsFuture, deadline);
        var result = new HashMap<Integer, Offsets>();
        for (var key : earliest.keySet()) {
            var committed = commits.get(key);
            result.put(key.partition(), new Offsets(starts.get(key).offset(), ends.get(key).offset(),
                    committed == null ? null : committed.offset()));
        }
        return Map.copyOf(result);
    }

    private static <T> T get(KafkaFuture<T> future, long deadline) throws Exception {
        return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    public record Offsets(long earliest, long end, Long committed) {
        long position() { return committed == null ? earliest : committed; }
    }

    @Override public void close() { admin.close(Duration.ofSeconds(1)); }
}
