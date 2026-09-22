package com.aayadi.payment.processor.adapter.kafka;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import com.aayadi.payment.processor.application.ProcessTransaction;
import com.aayadi.payment.processor.application.ProcessingResult;
import com.aayadi.payment.processor.domain.TransactionRules.RejectionReason;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import com.aayadi.payment.processor.application.LedgerStore;
import java.util.List;
import static com.aayadi.payment.processor.adapter.kafka.ProcessingTelemetry.Outcome.*;
import static com.aayadi.payment.processor.adapter.kafka.ProcessingTelemetry.safeId;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionReceivedListener {
    public static final String LISTENER_ID = "transaction-received";
    private final ProcessTransaction processor;
    private final ProcessingTelemetry telemetry;

    public TransactionReceivedListener(ProcessTransaction processor, ProcessingTelemetry telemetry) {
        this.processor = processor;
        this.telemetry = telemetry;
    }

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = "${payments.kafka.received-topic}")
    public void receive(ConsumerRecord<String, TransactionReceived> record) {
        var event = record.value();
        String correlationId = safeId(event == null ? null : event.correlationId());
        ProcessingResult outcome;
        try {
            outcome = processor.process(event);
        } catch (RuntimeException exception) {
            telemetry.record(TECHNICAL_FAILURE, record, List.of("PROCESSING_FAILURE"),
                    "Ledger processing failed correlationId=%s partition=%d offset=%d"
                            .formatted(correlationId, record.partition(), record.offset()));
            // Avoid putting raw events, SQL parameters, or database details in container error logs.
            throw new IllegalStateException("Ledger processing failed; offset must not be committed");
        }
        switch (outcome) {
            case ProcessingResult.Accepted accepted ->
                telemetry.record(accepted.outcome() == LedgerStore.Outcome.INSERTED ? ACCEPTED : DUPLICATE,
                        record, List.of(), "Ledger processing completed correlationId=%s transactionId=%s outcome=%s"
                                .formatted(correlationId, event.transactionId(), accepted.outcome()));
            case ProcessingResult.Rejected rejected -> {
                var reasons = rejected.reasons().stream().map(Enum::name).toList();
                String message = rejected.reasons().contains(RejectionReason.PAYLOAD_CONFLICT)
                        ? "Transaction rejected transactionId=%s correlationId=%s reason=PAYLOAD_CONFLICT"
                            .formatted(event.transactionId(), correlationId)
                        : "Transaction rejected correlationId=%s transactionId=%s reasons=%s"
                            .formatted(correlationId, event.transactionId(), rejected.reasons());
                telemetry.record(REJECTED, record, reasons, message);
            }
        }
        // Telemetry follows committed processing/publication, but precedes RECORD acknowledgement.
    }
}
