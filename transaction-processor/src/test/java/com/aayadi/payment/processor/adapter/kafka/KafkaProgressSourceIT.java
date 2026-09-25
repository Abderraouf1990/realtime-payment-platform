package com.aayadi.payment.processor.adapter.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class KafkaProgressSourceIT {
    @Test void observesAllPartitionsBeforeAnyListenerAssignmentWithoutCommitting() throws Exception {
        try (var kafka = new KafkaContainer("apache/kafka:4.1.1")) {
            kafka.start();
            Map<String, Object> properties = Map.of("bootstrap.servers", kafka.getBootstrapServers());
            try (var admin = Admin.create(properties);
                 var source = new KafkaProgressSource(new KafkaAdmin(properties), "progress-test", "unassigned-group");
                 var producer = new KafkaProducer<String, String>(properties, new StringSerializer(), new StringSerializer())) {
                admin.createTopics(List.of(new NewTopic("progress-test", 2, (short) 1)))
                        .all().get(10, TimeUnit.SECONDS);
                var meters = new SimpleMeterRegistry();
                try {
                    var metrics = new ConsumerProgressMetrics(source, meters, Clock.systemUTC());
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        metrics.sample();
                        assertThat(meters.get("payments.consumer.lag").gauge().value()).isZero();
                    });
                    producer.send(new ProducerRecord<>("progress-test", 0, "key", "first")).get(10, TimeUnit.SECONDS);
                    producer.send(new ProducerRecord<>("progress-test", 1, "key", "second")).get(10, TimeUnit.SECONDS);
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        metrics.sample();
                        assertThat(meters.get("payments.consumer.lag").gauge().value()).isEqualTo(2);
                    });
                    var offsets = source.read();
                    assertThat(offsets).hasSize(2);
                    assertThat(offsets.values()).allSatisfy(o -> {
                        assertThat(o.committed()).isNull();
                        assertThat(o.earliest()).isZero();
                        assertThat(o.end()).isEqualTo(1);
                    });
                    assertThat(meters.get("payments.consumer.progress.age").gauge().value()).isEqualTo(-1);
                    var committed = admin.listConsumerGroupOffsets("unassigned-group")
                            .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
                    assertThat(committed.get(new TopicPartition("progress-test", 0))).isNull();
                    assertThat(committed.get(new TopicPartition("progress-test", 1))).isNull();
                } finally {
                    meters.close();
                }
            }
        }
    }
}
