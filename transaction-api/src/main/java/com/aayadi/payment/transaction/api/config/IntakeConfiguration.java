package com.aayadi.payment.transaction.api.config;

import com.aayadi.payment.transaction.api.application.ReceiveTransaction;
import com.aayadi.payment.transaction.api.application.TransactionPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.ProducerListener;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class IntakeConfiguration {
    @Bean
    ProducerListener<Object, Object> producerListener() {
        LoggingProducerListener<Object, Object> listener = new LoggingProducerListener<>();
        listener.setIncludeContents(false);
        return listener;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ReceiveTransaction receiveTransaction(TransactionPublisher publisher, Clock clock) {
        return new ReceiveTransaction(publisher, clock);
    }
}
