# 🏦 Java Backend Internship Assignment - Idempotent Payment & Wallet Event Processor

[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Database](https://img.shields.io/badge/Database-H2%20In--Memory-blue.svg)](https://www.h2database.com/)
[![Build & Tests](https://img.shields.io/badge/Tests-JUnit%205%20Passing-success.svg)](https://junit.org/junit5/)

An internal, production-grade transaction ledger service built with **Java 17+**, **Spring Boot 3**, and an in-memory **H2 database**.

This service solves two of the most critical challenges in financial systems:
1. **Idempotent Webhook Processing**: Reliably handling duplicate webhook events sent simultaneously by payment gateways due to network retries, without double-debiting user funds.
2. **Concurrency & Race Condition Prevention**: Enforcing strict database-level pessimistic locking (`SELECT ... FOR UPDATE`) to eliminate double-spending and prevent negative balances during high-concurrency bursts.

---

## 📑 Table of Contents
- [1. Core Concepts (Beginner-Friendly Guide)](#1-core-concepts-beginner-friendly-guide)
  - [What is a Webhook & Why Do Duplicates Happen?](#what-is-a-webhook--why-do-duplicates-happen)
  - [What is Idempotency?](#what-is-idempotency)
  - [What is a Race Condition?](#what-is-a-race-condition)
  - [How Does Database-Level Locking Fix This?](#how-does-database-level-locking-fix-this)
  - [Why H2 In-Memory Database?](#why-h2-in-memory-database)
- [2. System Architecture & Flow](#2-system-architecture--flow)
- [3. Database Schema](#3-database-schema)
- [4. API Documentation](#4-api-documentation)
- [5. How to Run the Project & Tests](#5-how-to-run-the-project--tests)
  - [Zero-Config Evaluation in IntelliJ IDEA](#zero-config-evaluation-in-intellij-idea)
  - [Running via Command Line](#running-via-command-line)
- [6. Test Suite Details](#6-test-suite-details)
- [7. Project Structure](#7-project-structure)
- [8. Decision Log](#8-decision-log)

---

## 1. Core Concepts (Beginner-Friendly Guide)

If you are new to backend engineering, concurrency, or payment systems, this section explains the foundational concepts in simple terms.

### What is a Webhook & Why Do Duplicates Happen?
When a customer pays via a payment gateway (like Stripe, Razorpay, or PayPal), the gateway notifies our backend server by sending an HTTP POST request known as a **webhook**.

```
[Customer] ---> [Payment Gateway] ---> HTTP POST Webhook ---> [Our Service]
```

**Why do duplicate webhooks occur?**
- In distributed networks, packets can get delayed.
- If our server takes 500ms to process a request and the gateway timeout is 400ms, the gateway assumes the request was lost.
- The gateway's retry policy automatically resends the same request.
- As a result, 2 or 3 identical webhook payloads can arrive at our server **at the exact same millisecond**.
- If we do not handle this, the customer's wallet will be deducted twice for a single transaction!

---

### What is Idempotency?
> **Definition**: An operation is **idempotent** if performing it multiple times produces the exact same result as performing it once.

*Real-World Analogy:*
- **Elevator button**: If you press the "Floor 4" button once, the elevator goes to Floor 4. If you press it 5 times rapidly, the elevator still just goes to Floor 4. It does not go to Floor 20!
- In our API, each webhook contains a unique `transactionId` (UUID).
  - The **1st request** succeeds (`200 OK`) and deducts the balance.
  - The **2nd and 3rd identical requests** are caught as duplicates and return `409 Conflict` (or cached response) without changing the balance again.

---

### What is a Race Condition?
> **Definition**: A race condition happens when multiple threads access and modify shared data simultaneously, and the final outcome depends on the unpredictable timing/order of thread execution.

*The Classic Double-Spending Bug:*
Imagine User Bob has **₹500** in his wallet.
Two debit requests of **₹400** each arrive at the exact same millisecond:

```
Thread A (Request 1: ₹400)               Thread B (Request 2: ₹400)
---------------------------------       ---------------------------------
1. Reads Balance: ₹500                  1. Reads Balance: ₹500
2. Checks: ₹500 >= ₹400? YES!           2. Checks: ₹500 >= ₹400? YES!
3. Computes: ₹500 - ₹400 = ₹100         3. Computes: ₹500 - ₹400 = ₹100
4. Saves Balance: ₹100                  4. Saves Balance: ₹100
```

**Disaster!** Both requests succeeded, ₹800 worth of purchases were made, but the wallet still shows ₹100! Or worse, the balance goes negative.

---

### How Does Database-Level Locking Fix This?
We solve this using **Pessimistic Write Locking** (`SELECT ... FOR UPDATE`):

*Analogy:* Think of a public restroom with a physical lock on the door. Only one person can enter at a time. The next person in line waits outside until the door unlocks.

```
Thread A (Request 1)                    Thread B (Request 2)
---------------------------------       ---------------------------------
1. Executes:                            1. Executes:
   SELECT * FROM wallets                   SELECT * FROM wallets
   WHERE user_id = ? FOR UPDATE;           WHERE user_id = ? FOR UPDATE;
2. [ACQUIRES ROW LOCK]                  2. [BLOCKED - WAITING FOR LOCK]
3. Reads balance: ₹500                  ...
4. Deducts ₹400 -> Balance is ₹100      ...
5. Commits & Releases Lock              ...
                                        3. [UNBLOCKED - ACQUIRES LOCK]
                                        4. Reads updated balance: ₹100
                                        5. Checks: ₹100 >= ₹400? NO!
                                        6. Throws InsufficientFundsException
                                        7. Returns HTTP 400 Bad Request
```
Because the lock is enforced inside the **database engine**, no two requests can read or update the same user's balance simultaneously.

---

### Why H2 In-Memory Database?
- **Zero-Config Testing**: Reviewers do not need to install or configure PostgreSQL, MySQL, or Docker.
- The entire database runs purely in Java memory (RAM).
- It spins up instantly when tests start and clears when tests finish.

---

## 2. System Architecture & Flow

```
                      +------------------------------------------+
                      |         HTTP POST Webhook Request        |
                      +------------------------------------------+
                                           |
                                           v
                      +------------------------------------------+
                      |          TransactionController           |
                      |     (/api/v1/transactions/process)       |
                      +------------------------------------------+
                                           |
                                           v
                      +------------------------------------------+
                      |            TransactionService            |
                      +------------------------------------------+
                        /                                      \
                       /                                        \
         (Phase 1: Idempotency Check)             (Phase 2: Concurrency Lock)
                     v                                          v
      +-----------------------------+            +-----------------------------+
      |    TransactionRepository    |            |       WalletRepository      |
      |   existsById(txId)?         |            |  findByUserIdWithLock(...)  |
      |   - If YES -> 409 Conflict  |            |   (SELECT ... FOR UPDATE)   |
      +-----------------------------+            +-----------------------------+
                                                                |
                                                                v
                                                 +-----------------------------+
                                                 | Validate Balance & Deduct   |
                                                 | - Has funds? Deduct & Save  |
                                                 | - Low funds? 400 Bad Request|
                                                 +-----------------------------+
                                                                |
                                                                v
                                                 +-----------------------------+
                                                 | Save Transaction & Commit   |
                                                 +-----------------------------+
```

---

## 3. Database Schema

The application creates two tables automatically on startup:

### Table 1: `wallets`
Stores the current account balance for each user.
| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | UUID | PRIMARY KEY | Unique wallet ID |
| `user_id` | UUID | UNIQUE, NOT NULL | Foreign reference to the user |
| `balance` | DECIMAL(19, 2) | NOT NULL | Current funds (e.g. 500.00) |
| `created_at` | TIMESTAMP | NOT NULL | Record creation timestamp |
| `updated_at` | TIMESTAMP | NOT NULL | Last update timestamp |

### Table 2: `transactions`
Serves as an immutable ledger and idempotency registry.
| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `transaction_id` | UUID | PRIMARY KEY | Webhook idempotency key |
| `user_id` | UUID | NOT NULL | User associated with transaction |
| `amount` | DECIMAL(19, 2) | NOT NULL | Transaction amount |
| `type` | VARCHAR(20) | NOT NULL | `DEBIT` or `CREDIT` |
| `status` | VARCHAR(20) | NOT NULL | `SUCCESS` or `FAILED` |
| `created_at` | TIMESTAMP | NOT NULL | Ingestion timestamp |

---

## 4. API Documentation

### Process Transaction Webhook
Processes an incoming transaction event idempotently.

- **URL**: `POST /api/v1/transactions/process`
- **Content-Type**: `application/json`

#### Request Body
```json
{
  "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "userId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "amount": 250.00,
  "type": "DEBIT"
}
```

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `transactionId` | UUID | Yes | Unique idempotency identifier generated by the gateway |
| `userId` | UUID | Yes | Target user's account identifier |
| `amount` | Decimal | Yes | Transaction amount (minimum `0.01`, max 2 decimals) |
| `type` | String | Yes | `DEBIT` or `CREDIT` |

#### Responses

##### 🟢 200 OK (Success)
Returned when the transaction is processed successfully:
```json
{
  "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "userId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "amount": 250.00,
  "type": "DEBIT",
  "status": "SUCCESS",
  "balance": 250.00,
  "timestamp": "2026-09-08T11:20:00Z"
}
```

##### 🟡 409 Conflict (Duplicate Request / Idempotency Hit)
Returned when a request with the same `transactionId` has already been processed or is currently in flight:
```json
{
  "status": 409,
  "error": "Conflict",
  "message": "Transaction already processed with ID: 3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "timestamp": "2026-09-08T11:20:01Z"
}
```

##### 🔴 400 Bad Request (Insufficient Funds)
Returned when the user does not have sufficient balance for a `DEBIT` operation:
```json
{
  "status": 400,
  "error": "Insufficient Funds",
  "message": "Insufficient funds for user 7c9e6679-7425-40de-944b-e07fc1f90ae7: current balance 100.00 is less than requested amount 250.00",
  "transactionId": null,
  "timestamp": "2026-09-08T11:20:02Z"
}
```

---

## 5. How to Run the Project & Tests

### Zero-Config Evaluation in IntelliJ IDEA

The test suite is designed for instant evaluation in IntelliJ IDEA without configuring databases or third-party tools.

1. Open **IntelliJ IDEA**.
2. Click **File -> Open...** and select this project directory.
3. Wait 10–20 seconds for IntelliJ to index and detect Maven dependencies automatically.
4. In the Project tool window, navigate to:
   `src/test/java/com/assignment/ledger/TransactionIntegrationTest.java`
5. Right-click on the `TransactionIntegrationTest` class (or click the green double-arrow next to the class name) and select:
   **`Run 'TransactionIntegrationTest'`**
6. All 3 tests will execute and pass, printing formatted logs with their intent and results directly to the Run Console.

---

### Running via Command Line

You can run the entire test suite using Maven or the included Maven Wrapper:

#### Using Maven:
```bash
mvn clean test
```

#### Using Maven Wrapper (Windows):
```cmd
mvnw.cmd clean test
```

#### Using Maven Wrapper (macOS / Linux):
```bash
./mvnw clean test
```

### Accessing the H2 In-Memory Web Console

When running the application (`mvn spring-boot:run` or via your IDE), you can inspect the live database tables directly in your browser:

1. Open your browser and navigate to: **`http://localhost:8080/h2-console`**
2. Use the following connection settings:
   - **Saved Settings**: `Generic H2 (Embedded)`
   - **Driver Class**: `org.h2.Driver`
   - **JDBC URL**: `jdbc:h2:mem:testdb` *(Default)*
   - **User Name**: `abhi`
   - **Password**: `000`
3. Click **Connect**. You can query `WALLETS` and `TRANSACTIONS` tables directly.

---

## 6. Test Suite Details

All integration tests are written in JUnit 5 with clear `@DisplayName` annotations as required by the assignment:

### Test 1: The Happy Path Test
- **DisplayName**: `"Processes a single valid debit transaction successfully."`
- **Scenario**:
  - Initializes a wallet with **INR 500.00**.
  - Sends a single `DEBIT` request of **INR 250.00**.
- **Verification**:
  - HTTP Status: `200 OK`.
  - Final wallet balance in database: **INR 250.00**.
  - Transaction record persisted with status `SUCCESS`.

### Test 2: The Idempotency Test
- **DisplayName**: `"Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once."`
- **Scenario**:
  - Initializes a wallet with **INR 500.00**.
  - Uses a `CountDownLatch` and multi-threaded `ExecutorService` to fire 3 concurrent HTTP requests sharing the **exact same `transactionId`** within milliseconds.
- **Verification**:
  - Exactly **1 request** receives `200 OK`.
  - Exactly **2 requests** receive `409 Conflict`.
  - Final wallet balance in database: **INR 400.00** (deducted exactly once).
  - Database contains exactly **1** transaction record.

### Test 3: The Race Condition Test
- **DisplayName**: `"Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds."`
- **Scenario**:
  - Initializes a wallet with **INR 500.00**.
  - Fires **10 concurrent debit requests** of **INR 100.00** each (with unique `transactionId`s for the same user) using `CountDownLatch`.
- **Verification**:
  - Exactly **5 requests** succeed with `200 OK`.
  - Exactly **5 requests** fail with `400 Bad Request` (`Insufficient Funds`).
  - Final wallet balance in database: **INR 0.00** (never negative).

---

### Actual Test Run Console Output Sample
```text
================================================================================
[TEST 1: Happy Path]
Intent: Processes a single valid debit transaction successfully.
Setup: Initialized wallet for user c755eb57-0bbd-454d-b5a0-ec38f3112255 with balance INR 500.00
Action: Sent POST http://localhost:62234/api/v1/transactions/process for amount INR 250.00
Response Status: 200 OK | Remaining Balance: INR 250.00
Result: PASSED - Balance deducted correctly and transaction recorded.
================================================================================

================================================================================
[TEST 2: Idempotency]
Intent: Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.
Setup: Initialized wallet for user 3a416610-c451-48e7-94e9-687812c10633 with balance INR 500.00
Action: Firing 3 concurrent requests with identical transactionId (462dfecb-a1e9-482f-bb72-41b7e5d5787e)...
Response Status Codes: [200, 409, 409]
Successful (200 OK): 1 | Conflicts (409): 2
Expected Balance: INR 400.00 | Actual Final Balance: INR 400.00
Result: PASSED - Exactly 1 request processed, 2 duplicates rejected with 409 Conflict. Balance deducted only once.
================================================================================

================================================================================
[TEST 3: Race Condition]
Intent: Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.
Setup: Initialized wallet for user 50f9b439-3287-4d47-a817-aa19de0bafff with balance INR 500.00
Action: Firing 10 concurrent debit requests of INR 100.00 each...
All Response Status Codes: [200, 200, 200, 200, 200, 400, 400, 400, 400, 400]
Successful (200 OK): 5
Failed Insufficient Funds (400 Bad Request): 5
Expected Final Balance: INR 0.00 | Actual Final Balance: INR 0.00
Result: PASSED - Database locking strictly serialized balance updates. Final balance is exactly INR 0.00 with no negative balance.
================================================================================
```

---

## 7. Project Structure

```
├── pom.xml                                        # Maven project configuration
├── README.md                                      # Project guide and architecture documentation
├── DECISIONS.md                                   # Decision log answering required submission prompts
├── .gitignore                                     # Git ignore rules
├── src
│   ├── main
│   │   ├── java/com/assignment/ledger
│   │   │   ├── Application.java                   # Spring Boot entry point
│   │   │   ├── controller
│   │   │   │   ├── TransactionController.java     # Endpoint: POST /api/v1/transactions/process
│   │   │   │   └── WalletController.java          # Helper endpoints for wallet creation/lookup
│   │   │   ├── dto
│   │   │   │   ├── TransactionRequest.java        # Request validation payload
│   │   │   │   ├── TransactionResponse.java       # Success response payload
│   │   │   │   └── ErrorResponse.java             # Error response payload
│   │   │   ├── entity
│   │   │   │   ├── Wallet.java                    # Wallet domain entity (balance)
│   │   │   │   ├── TransactionRecord.java         # Ledger transaction entity
│   │   │   │   ├── TransactionType.java           # DEBIT / CREDIT enum
│   │   │   │   └── TransactionStatus.java         # SUCCESS / FAILED enum
│   │   │   ├── exception
│   │   │   │   ├── GlobalExceptionHandler.java    # Centralized REST error handling
│   │   │   │   ├── DuplicateTransactionException.java # 409 Conflict
│   │   │   │   ├── InsufficientFundsException.java    # 400 Bad Request
│   │   │   │   └── WalletNotFoundException.java       # 404 Not Found
│   │   │   ├── repository
│   │   │   │   ├── WalletRepository.java          # JPA repo with @Lock(PESSIMISTIC_WRITE)
│   │   │   │   └── TransactionRepository.java     # JPA repo for transactions
│   │   │   └── service
│   │   │       ├── TransactionService.java        # Business logic for locking & idempotency
│   │   │       └── WalletService.java             # Wallet management
│   │   └── resources
│   │       └── application.yml                    # Spring Boot & H2 database configuration
│   └── test
│       ├── java/com/assignment/ledger
│       │   └── TransactionIntegrationTest.java    # JUnit 5 zero-config integration test suite
│       └── resources
│           └── application-test.yml               # Test-specific profile configuration
```

---

## 8. Decision Log

For detailed technical justification on:
1. Handling the concurrency race condition via database locking, and
2. Technical review of AI assistant suggestions during development,

Please refer to [`DECISIONS.md`](./DECISIONS.md).
