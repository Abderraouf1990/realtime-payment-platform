package com.aayadi.payment.processor.adapter.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import java.util.List;

/** Stop without recovering/skipping failed records; never log raw deserialization exceptions. */
public class StopOnFailureErrorHandler extends CommonContainerStoppingErrorHandler {
    @Override
    public boolean isAckAfterHandle() {
        return false;
    }

    @Override
    public void handleRemaining(Exception exception, List<ConsumerRecord<?, ?>> records,
                                Consumer<?, ?> consumer, MessageListenerContainer container) {
        super.handleRemaining(sanitized(), records, consumer, container);
    }

    @Override
    public void handleOtherException(Exception exception, Consumer<?, ?> consumer,
                                     MessageListenerContainer container, boolean batchListener) {
        super.handleOtherException(sanitized(), consumer, container, batchListener);
    }

    private static IllegalStateException sanitized() {
        return new IllegalStateException("Kafka consumer stopped after a processing or deserialization failure");
    }
}
