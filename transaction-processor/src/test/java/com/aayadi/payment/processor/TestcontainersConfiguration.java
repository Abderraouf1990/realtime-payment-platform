package com.aayadi.payment.processor;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    NewTopic rejectedTopic(@org.springframework.beans.factory.annotation.Value("${payments.kafka.rejected-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(1).replicas(1).build();
    }
	@Bean
	NewTopic receivedTopic() {
		return TopicBuilder.name("transactions.received").partitions(1).replicas(1).build();
	}

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.1"));
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17.6"));
	}

}
