package com.aayadi.payment.transaction.api.adapter.web;

import com.aayadi.payment.transaction.api.application.ReceiveTransaction;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {
    private final ReceiveTransaction receiveTransaction;

    public TransactionController(ReceiveTransaction receiveTransaction) {
        this.receiveTransaction = receiveTransaction;
    }

    @PostMapping
    public ResponseEntity<AcceptedTransaction> receive(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransactionRequest request) {
        if (!idempotencyKey.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (!idempotencyKey.equals(request.transactionId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        String transactionId = receiveTransaction.receive(request.transactionId(), request.correlationId(), request.accountId(),
                request.amount(), request.currency(), request.type());
        return ResponseEntity.accepted().body(new AcceptedTransaction(transactionId, request.correlationId()));
    }

    public record AcceptedTransaction(String transactionId, String correlationId) {
    }
}
