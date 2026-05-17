package com.assignment.ledger.exception;

import java.util.UUID;

public class DuplicateTransactionException extends RuntimeException {

    private final UUID transactionId;

    public DuplicateTransactionException(UUID transactionId) {
        super("Transaction already processed with ID: " + transactionId);
        this.transactionId = transactionId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }
}
