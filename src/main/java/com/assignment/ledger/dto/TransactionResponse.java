package com.assignment.ledger.dto;

import com.assignment.ledger.entity.TransactionStatus;
import com.assignment.ledger.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        UUID userId,
        BigDecimal amount,
        TransactionType type,
        TransactionStatus status,
        BigDecimal balance,
        Instant timestamp
) {}
