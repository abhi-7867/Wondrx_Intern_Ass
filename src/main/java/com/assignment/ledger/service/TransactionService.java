package com.assignment.ledger.service;

import com.assignment.ledger.dto.TransactionRequest;
import com.assignment.ledger.dto.TransactionResponse;
import com.assignment.ledger.entity.*;
import com.assignment.ledger.exception.*;
import com.assignment.ledger.repository.TransactionRepository;
import com.assignment.ledger.repository.WalletRepository;
import org.springframework.stereotype.Service;

@Service
public class TransactionService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(WalletRepository walletRepository, TransactionRepository transactionRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }
}
