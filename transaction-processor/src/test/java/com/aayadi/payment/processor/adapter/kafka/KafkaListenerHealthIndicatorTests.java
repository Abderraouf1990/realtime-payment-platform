package com.aayadi.payment.processor.adapter.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaListenerHealthIndicatorTests {
    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final KafkaListenerHealthIndicator health = new KafkaListenerHealthIndicator(registry);

    @Test
    void startupIsNotReportedAsReadyEvenWhenContainerIsRunning() {
        runningContainer();
        assertHealth(Status.OUT_OF_SERVICE, "STARTING");
    }

    @Test
    void missingExpectedListenerFailsClosedAfterStartup() {
        health.applicationReady();
        assertHealth(Status.DOWN, "MISSING");
    }

    @Test
    void runningListenerIsUp() {
        runningContainer();
        health.applicationReady();
        assertHealth(Status.UP, "RUNNING");
    }

    @Test
    void evenANormalStopMakesProcessingUnavailable() {
        runningContainer();
        health.applicationReady();
        when(container.isRunning()).thenReturn(false);
        assertHealth(Status.DOWN, "STOPPED");
    }

    @Test
    void abnormallyStoppedChildIsDetectedWhileParentStillRuns() {
        runningContainer();
        health.applicationReady();
        when(container.isInExpectedState()).thenReturn(false);
        assertHealth(Status.DOWN, "STOPPED");
    }

    @Test
    void pausedListenerIsNotReady() {
        runningContainer();
        health.applicationReady();
        when(container.isPauseRequested()).thenReturn(true);
        assertHealth(Status.OUT_OF_SERVICE, "PAUSED");
    }

    @Test
    void manualRestartRestoresHealthWithoutLatchingTheFailure() {
        runningContainer();
        health.applicationReady();
        when(container.isRunning()).thenReturn(false);
        assertHealth(Status.DOWN, "STOPPED");
        when(container.isRunning()).thenReturn(true);
        assertHealth(Status.UP, "RUNNING");
    }

    private void runningContainer() {
        when(registry.getListenerContainer(TransactionReceivedListener.LISTENER_ID)).thenReturn(container);
        when(container.isRunning()).thenReturn(true);
        when(container.isInExpectedState()).thenReturn(true);
    }

    private void assertHealth(Status status, String state) {
        var result = health.health();
        assertThat(result.getStatus()).isEqualTo(status);
        // Only an allowlisted state is exposed: no exception, payload or connection details.
        assertThat(result.getDetails()).containsOnlyKeys("state").containsEntry("state", state);
    }
}
