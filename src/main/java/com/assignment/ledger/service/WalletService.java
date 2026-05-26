package com.assignment.ledger.service;

import com.assignment.ledger.entity.Wallet;
import com.assignment.ledger.exception.WalletNotFoundException;
import com.assignment.ledger.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional
    public Wallet createWallet(UUID userId, BigDecimal initialBalance) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> walletRepository.save(new Wallet(UUID.randomUUID(), userId, initialBalance)));
    }

    @Transactional(readOnly = true)
    public Wallet getWalletByUserId(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));
    }

    @Transactional(readOnly = true)
    public BigDecimal getBalance(UUID userId) {
        return getWalletByUserId(userId).getBalance();
    }
}
