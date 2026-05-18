package com.assignment.ledger.dto;

import java.time.Instant;
import java.util.UUID;

public record ErrorResponse(
        int status,
        String error,
        String message,
        UUID transactionId,
        Instant timestamp
) {
    public ErrorResponse(int status, String error, String message) {
        this(status, error, message, null, Instant.now());
    }

    public ErrorResponse(int status, String error, String message, UUID transactionId) {
        this(status, error, message, transactionId, Instant.now());
    }
}
