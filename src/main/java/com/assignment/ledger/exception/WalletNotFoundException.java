package com.assignment.ledger.exception;

import java.util.UUID;

public class WalletNotFoundException extends RuntimeException {

    private final UUID userId;

    public WalletNotFoundException(UUID userId) {
        super("Wallet not found for user ID: " + userId);
        this.userId = userId;
    }

    public UUID getUserId() {
        return userId;
    }
}
