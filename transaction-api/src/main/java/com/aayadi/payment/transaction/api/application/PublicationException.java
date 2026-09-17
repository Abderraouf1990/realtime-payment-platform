package com.aayadi.payment.transaction.api.application;

public class PublicationException extends RuntimeException {
    public PublicationException(Throwable cause) {
        super("Transaction publication could not be confirmed", cause);
    }
}
