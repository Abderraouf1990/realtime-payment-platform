package com.aayadi.payment.processor.config;

import com.aayadi.payment.processor.adapter.kafka.StopOnFailureErrorHandler;
import com.aayadi.payment.processor.application.LedgerStore;
import com.aayadi.payment.processor.application.ProcessTransaction;
import com.aayadi.payment.processor.application.RejectionStore;
import com.aayadi.payment.processor.application.RejectionPublisher;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.kafka.support.LoggingProducerListener;
import com.aayadi.payment.processor.domain.TransactionRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.kafka.listener.CommonErrorHandler;
import javax.sql.DataSource;
import java.time.Clock;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ProcessorConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ProcessTransaction processTransaction(LedgerStore store, Clock clock, RejectionStore rejections, RejectionPublisher publisher) {
        return new ProcessTransaction(store, clock, new TransactionRules(), rejections, publisher);
    }

    @Bean
    ProducerListener<Object, Object> producerListener() {
        var listener = new LoggingProducerListener<Object, Object>();
        listener.setIncludeContents(false);
        return listener;
    }

    @Bean
    JdbcTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        return new StopOnFailureErrorHandler();
    }
}
