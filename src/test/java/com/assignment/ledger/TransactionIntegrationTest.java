package com.assignment.ledger;

import com.assignment.ledger.dto.TransactionRequest;
import com.assignment.ledger.dto.TransactionResponse;
import com.assignment.ledger.entity.TransactionType;
import com.assignment.ledger.entity.Wallet;
import com.assignment.ledger.repository.TransactionRepository;
import com.assignment.ledger.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TransactionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/api/v1/transactions/process";
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
    }

    @Test
    @DisplayName("Processes a single valid debit transaction successfully.")
    void testHappyPath() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("[TEST 1: Happy Path]");
        System.out.println("Intent: Processes a single valid debit transaction successfully.");

        UUID userId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("500.00");
        BigDecimal debitAmount = new BigDecimal("250.00");

        walletRepository.save(new Wallet(UUID.randomUUID(), userId, initialBalance));
        System.out.printf("Setup: Initialized wallet for user %s with balance INR %s%n", userId, initialBalance);

        TransactionRequest request = new TransactionRequest(
                txId,
                userId,
                debitAmount,
                TransactionType.DEBIT
        );

        ResponseEntity<TransactionResponse> response = restTemplate.postForEntity(
                baseUrl,
                request,
                TransactionResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().balance()).isEqualByComparingTo(new BigDecimal("250.00"));

        Wallet updatedWallet = walletRepository.findByUserId(userId).orElseThrow();
        assertThat(updatedWallet.getBalance()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(transactionRepository.existsById(txId)).isTrue();

        System.out.printf("Action: Sent POST %s for amount INR %s%n", baseUrl, debitAmount);
        System.out.printf("Response Status: %s | Remaining Balance: INR %s%n", response.getStatusCode(), response.getBody().balance());
        System.out.println("Result: PASSED - Balance deducted correctly and transaction recorded.");
        System.out.println("=".repeat(80));
    }

    @Test
    @DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
    void testIdempotencyConcurrentDuplicateRequests() throws InterruptedException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("[TEST 2: Idempotency]");
        System.out.println("Intent: Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.");

        UUID userId = UUID.randomUUID();
        UUID duplicateTxId = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("500.00");
        BigDecimal debitAmount = new BigDecimal("100.00");

        walletRepository.save(new Wallet(UUID.randomUUID(), userId, initialBalance));
        System.out.printf("Setup: Initialized wallet for user %s with balance INR %s%n", userId, initialBalance);

        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);

        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await(); // Hold all threads so they fire simultaneously
                    TransactionRequest request = new TransactionRequest(
                            duplicateTxId,
                            userId,
                            debitAmount,
                            TransactionType.DEBIT
                    );
                    ResponseEntity<String> response = restTemplate.postForEntity(baseUrl, request, String.class);
                    statusCodes.add(response.getStatusCode().value());

                    if (response.getStatusCode() == HttpStatus.OK) {
                        successCount.incrementAndGet();
                    } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.println("Thread execution error: " + e.getMessage());
                } finally {
                    endGate.countDown();
                }
            });
        }

        // Fire all 3 threads simultaneously
        System.out.printf("Action: Firing %d concurrent requests with identical transactionId (%s)...%n",
                threadCount, duplicateTxId);
        startGate.countDown();
        boolean completed = endGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();

        Wallet finalWallet = walletRepository.findByUserId(userId).orElseThrow();

        System.out.println("Response Status Codes: " + statusCodes);
        System.out.printf("Successful (200 OK): %d | Conflicts (409): %d%n", successCount.get(), conflictCount.get());
        System.out.printf("Expected Balance: INR 400.00 | Actual Final Balance: INR %s%n", finalWallet.getBalance());

        // Exactly one must succeed, the other two must return 409 Conflict
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(2);

        // Balance must only be deducted once
        assertThat(finalWallet.getBalance()).isEqualByComparingTo(new BigDecimal("400.00"));

        // Only one transaction record must exist
        assertThat(transactionRepository.count()).isEqualTo(1);

        System.out.println("Result: PASSED - Exactly 1 request processed, 2 duplicates rejected with 409 Conflict. Balance deducted only once.");
        System.out.println("=".repeat(80));
    }

    @Test
    @DisplayName("Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.")
    void testRaceConditionConcurrentDebitsExceedingBalance() throws InterruptedException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("[TEST 3: Race Condition]");
        System.out.println("Intent: Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.");

        UUID userId = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("500.00");
        BigDecimal debitPerRequest = new BigDecimal("100.00");

        walletRepository.save(new Wallet(UUID.randomUUID(), userId, initialBalance));
        System.out.printf("Setup: Initialized wallet for user %s with balance INR %s%n", userId, initialBalance);

        int totalRequests = 10;
        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(totalRequests);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startGate.await(); // Synchronize all 10 threads to hit at the exact same millisecond
                    TransactionRequest request = new TransactionRequest(
                            UUID.randomUUID(), // Distinct transactionId for each request
                            userId,
                            debitPerRequest,
                            TransactionType.DEBIT
                    );
                    ResponseEntity<String> response = restTemplate.postForEntity(baseUrl, request, String.class);
                    statusCodes.add(response.getStatusCode().value());

                    if (response.getStatusCode() == HttpStatus.OK) {
                        successCount.incrementAndGet();
                    } else if (response.getStatusCode() == HttpStatus.BAD_REQUEST) {
                        insufficientFundsCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.println("Thread execution error: " + e.getMessage());
                } finally {
                    endGate.countDown();
                }
            });
        }

        System.out.printf("Action: Firing %d concurrent debit requests of INR %s each...%n",
                totalRequests, debitPerRequest);
        startGate.countDown();
        boolean completed = endGate.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();

        Wallet finalWallet = walletRepository.findByUserId(userId).orElseThrow();

        System.out.println("All Response Status Codes: " + statusCodes);
        System.out.printf("Successful (200 OK): %d%n", successCount.get());
        System.out.printf("Failed Insufficient Funds (400 Bad Request): %d%n", insufficientFundsCount.get());
        System.out.printf("Expected Final Balance: INR 0.00 | Actual Final Balance: INR %s%n", finalWallet.getBalance());

        // 5 requests of 100 must succeed from 500 balance
        assertThat(successCount.get()).isEqualTo(5);
        // The remaining 5 requests must fail with insufficient funds
        assertThat(insufficientFundsCount.get()).isEqualTo(5);
        // Final balance must be exactly 0.00, never negative
        assertThat(finalWallet.getBalance()).isEqualByComparingTo(new BigDecimal("0.00"));

        System.out.println("Result: PASSED - Database locking strictly serialized balance updates. Final balance is exactly INR 0.00 with no negative balance.");
        System.out.println("=".repeat(80));
    }
}

}
