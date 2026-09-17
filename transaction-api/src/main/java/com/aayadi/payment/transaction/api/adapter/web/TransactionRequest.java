package com.aayadi.payment.transaction.api.adapter.web;

import com.aayadi.payment.contracts.v1.TransactionType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TransactionRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String transactionId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String correlationId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String accountId,
        @NotNull @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull TransactionType type) {
}
