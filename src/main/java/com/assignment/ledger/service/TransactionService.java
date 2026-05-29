package com.assignment.ledger.service;

import com.assignment.ledger.dto.TransactionRequest;
import com.assignment.ledger.dto.TransactionResponse;
import com.assignment.ledger.entity.TransactionRecord;
import com.assignment.ledger.entity.TransactionStatus;
import com.assignment.ledger.entity.TransactionType;
import com.assignment.ledger.entity.Wallet;
import com.assignment.ledger.exception.DuplicateTransactionException;
import com.assignment.ledger.exception.InsufficientFundsException;
import com.assignment.ledger.exception.WalletNotFoundException;
import com.assignment.ledger.repository.TransactionRepository;
import com.assignment.ledger.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(WalletRepository walletRepository,
                              TransactionRepository transactionRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResponse processTransaction(TransactionRequest request) {
        // Fast-path idempotency check: reject if already committed
        if (transactionRepository.existsById(request.transactionId())) {
            throw new DuplicateTransactionException(request.transactionId());
        }

        // Acquire exclusive database-level row lock on the user's wallet record.
        // Any concurrent transaction for the same user will wait here until this transaction commits or rolls back.
        Wallet wallet = walletRepository.findByUserIdWithLock(request.userId())
                .orElseThrow(() -> new WalletNotFoundException(request.userId()));

        // Second check after acquiring the lock to protect against concurrent webhook retries
        // arriving within milliseconds of each other
        if (transactionRepository.existsById(request.transactionId())) {
            throw new DuplicateTransactionException(request.transactionId());
        }

        if (request.type() == TransactionType.DEBIT) {
            if (!wallet.hasSufficientBalance(request.amount())) {
                throw new InsufficientFundsException(
                        wallet.getUserId(),
                        wallet.getBalance(),
                        request.amount()
                );
            }
            wallet.deduct(request.amount());
        } else if (request.type() == TransactionType.CREDIT) {
            wallet.credit(request.amount());
        }

        walletRepository.saveAndFlush(wallet);

        TransactionRecord record = new TransactionRecord(
                request.transactionId(),
                request.userId(),
                request.amount(),
                request.type(),
                TransactionStatus.SUCCESS
        );
        transactionRepository.saveAndFlush(record);

        return new TransactionResponse(
                record.getTransactionId(),
                record.getUserId(),
                record.getAmount(),
                record.getType(),
                record.getStatus(),
                wallet.getBalance(),
                record.getCreatedAt()
        );
    }
}
