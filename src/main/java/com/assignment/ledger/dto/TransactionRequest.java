package com.assignment.ledger.dto;

import com.assignment.ledger.entity.TransactionType;
import java.math.BigDecimal;
import java.util.UUID;

public record TransactionRequest(
    UUID transactionId,
    UUID userId,
    BigDecimal amount,
    TransactionType type
) {}
