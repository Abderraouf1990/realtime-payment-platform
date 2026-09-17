package com.aayadi.payment.transaction.api.adapter.web;

import com.aayadi.payment.contracts.v1.TransactionReceived;
import com.aayadi.payment.transaction.api.application.PublicationException;
import com.aayadi.payment.transaction.api.application.TransactionPublisher;
import com.aayadi.payment.transaction.api.config.IntakeConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
@Import(IntakeConfiguration.class)
class TransactionControllerTests {
    private static final String BODY = """
            {"transactionId":"TX-1","correlationId":"CORR-1","accountId":"ACC-1","amount":250.00,"currency":"EUR","type":"TRANSFER"}
            """;
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private TransactionPublisher publisher;

    @Test
    void acceptsValidRequest() throws Exception {
        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "TX-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.transactionId").value("TX-1"))
                .andExpect(jsonPath("$.correlationId").value("CORR-1"));
        var event = ArgumentCaptor.forClass(TransactionReceived.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().correlationId()).isEqualTo("CORR-1");
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void rejectsMissingCorrelationId() throws Exception {
        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "TX-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"correlationId\":\"CORR-1\",", "")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\" \"", "\"bad/id\"", "\"bad\\nline\"",
            "\"12345678901234567890123456789012345678901234567890123456789012345\""})
    void rejectsInvalidCorrelationId(String value) throws Exception {
        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "TX-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"CORR-1\"", value)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
    }

    @Test
    void preservesCorrelationIdAtMaximumLength() throws Exception {
        String correlationId = "C".repeat(64);
        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "TX-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("CORR-1", correlationId)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.correlationId").value(correlationId));
        var event = ArgumentCaptor.forClass(TransactionReceived.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().correlationId()).isEqualTo(correlationId);
    }

    @Test
    void correlationDoesNotReplaceBusinessIdentityOrLeakBetweenRequests() throws Exception {
        for (String correlationId : new String[] {"CORR-1", "CORR-2"}) {
            mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "TX-1")
                            .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("CORR-1", correlationId)))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.transactionId").value("TX-1"))
                    .andExpect(jsonPath("$.correlationId").value(correlationId));
        }
        var events = ArgumentCaptor.forClass(TransactionReceived.class);
        verify(publisher, times(2)).publish(events.capture());
        assertThat(events.getAllValues()).extracting(TransactionReceived::correlationId).containsExactly("CORR-1", "CORR-2");
        assertThat(events.getAllValues()).extracting(TransactionReceived::transactionId).containsOnly("TX-1");
    }

    @Test
    void changedPayloadWithExistingIdIsNotDeduplicatedByStatelessIntake() throws Exception {
        for (String body : new String[] {BODY, BODY.replace("250.00", "500.00")}) {
            mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "TX-1")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.transactionId").value("TX-1"));
        }
        var events = ArgumentCaptor.forClass(TransactionReceived.class);
        verify(publisher, times(2)).publish(events.capture());
        assertThat(events.getAllValues()).extracting(TransactionReceived::transactionId).containsExactly("TX-1", "TX-1");
        assertThat(events.getAllValues().get(0).amount()).isEqualByComparingTo("250.00");
        assertThat(events.getAllValues().get(1).amount()).isEqualByComparingTo("500.00");
    }

    @Test
    void requiresIdempotencyHeader() throws Exception {
        mvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "invalid key", "x/x"})
    void rejectsMalformedIdempotencyHeader(String key) throws Exception {
        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsMismatchedIdentity() throws Exception {
        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "TX-2")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Idempotency-Key must match transactionId."));
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "{broken",
            "{\"transactionId\":\"TX-1\",\"accountId\":\" \",\"amount\":1,\"currency\":\"EUR\",\"type\":\"TRANSFER\"}",
            "{\"transactionId\":\"TX-1\",\"accountId\":\"ACC-1\",\"amount\":null,\"currency\":\"EUR\",\"type\":\"TRANSFER\"}",
            "{\"transactionId\":\"TX-1\",\"accountId\":\"ACC-1\",\"amount\":1.234,\"currency\":\"EUR\",\"type\":\"TRANSFER\"}",
            "{\"transactionId\":\"TX-1\",\"accountId\":\"ACC-1\",\"amount\":1000000000000000,\"currency\":\"EUR\",\"type\":\"TRANSFER\"}",
            "{\"transactionId\":\"TX-1\",\"accountId\":\"ACC-1\",\"amount\":1,\"currency\":\"eur\",\"type\":\"TRANSFER\"}",
            "{\"transactionId\":\"TX-1\",\"accountId\":\"ACC-1\",\"amount\":1,\"currency\":\"EUR\",\"type\":\"UNKNOWN\"}"
    })
    void rejectsInvalidBodiesWithoutPublishing(String body) throws Exception {
        // Keep the correlation ID valid so these cases still exercise their original invalid fields.
        body = body.replace("\"transactionId\":\"TX-1\",", "\"transactionId\":\"TX-1\",\"correlationId\":\"CORR-1\",");
        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "TX-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString("ACC-1"))));
        verifyNoInteractions(publisher);
    }

    @Test
    void businessValidationIsDeferredToProcessor() throws Exception {
        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "TX-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("250.00", "-1.00")))
                .andExpect(status().isAccepted());
        verify(publisher).publish(any(TransactionReceived.class));
    }

    @Test
    void reportsUnconfirmedPublicationWithoutLeakingInternals() throws Exception {
        doThrow(new PublicationException(new IllegalStateException("sensitive internal details")))
                .when(publisher).publish(any());
        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "TX-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("Retry the same payload")))
                .andExpect(content().string(not(containsString("sensitive"))));
    }
}
