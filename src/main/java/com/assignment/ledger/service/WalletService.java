package com.assignment.ledger.service;

import com.assignment.ledger.entity.Wallet;
import com.assignment.ledger.exception.WalletNotFoundException;
import com.assignment.ledger.repository.WalletRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    public Wallet createWallet(UUID userId, BigDecimal initialBalance, String currency) {
        Wallet wallet = new Wallet(userId, initialBalance, currency);
        return walletRepository.save(wallet);
    }

    public Wallet getWalletByUserId(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet not found for user: " + userId));
    }
}
