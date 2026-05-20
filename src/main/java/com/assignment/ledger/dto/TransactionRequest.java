package com.assignment.ledger.dto;

import com.assignment.ledger.entity.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionRequest(
        @NotNull(message = "transactionId is required")
        UUID transactionId,

        @NotNull(message = "userId is required")
        UUID userId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be greater than 0")
        @Digits(integer = 15, fraction = 2, message = "amount can have at most 2 decimal places")
        BigDecimal amount,

        @NotNull(message = "type is required (DEBIT or CREDIT)")
        TransactionType type
) {}
