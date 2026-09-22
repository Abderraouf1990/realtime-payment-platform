package com.aayadi.payment.processor.adapter.kafka;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import com.aayadi.payment.processor.application.ProcessTransaction;
import com.aayadi.payment.processor.application.ProcessingResult;
import com.aayadi.payment.processor.domain.TransactionRules.RejectionReason;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionReceivedListener {
    public static final String LISTENER_ID = "transaction-received";
    private static final Logger LOG = LoggerFactory.getLogger(TransactionReceivedListener.class);
    private final ProcessTransaction processor;

    public TransactionReceivedListener(ProcessTransaction processor) {
        this.processor = processor;
    }

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = "${payments.kafka.received-topic}")
    public void receive(ConsumerRecord<String, TransactionReceived> record) {
        var event = record.value();
        String correlationId = safeId(event == null ? null : event.correlationId());
        try {
            var outcome = processor.process(event);
            switch (outcome) {
                case ProcessingResult.Accepted accepted ->
                    LOG.info("Ledger processing completed correlationId={} transactionId={} outcome={}",
                            correlationId, event.transactionId(), accepted.outcome());
                case ProcessingResult.Rejected rejected -> {
                    if (rejected.reasons().contains(RejectionReason.PAYLOAD_CONFLICT)) {
                        LOG.info("Transaction rejected transactionId={} correlationId={} reason=PAYLOAD_CONFLICT",
                                event.transactionId(), correlationId);
                    } else {
                        LOG.info("Transaction rejected correlationId={} transactionId={} reasons={}",
                            correlationId, event.transactionId(), rejected.reasons());
                    }
                }
            }
            // Rejections also wait for broker confirmation after the database commit, before RECORD ack.
        } catch (RuntimeException exception) {
            LOG.warn("Ledger processing failed correlationId={} partition={} offset={} failureType={}",
                    correlationId, record.partition(), record.offset(), exception.getClass().getSimpleName());
            // Avoid putting raw events, SQL parameters, or database details in container error logs.
            throw new IllegalStateException("Ledger processing failed; offset must not be committed");
        }
    }

    private static String safeId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}") ? value : "unavailable";
    }
}
