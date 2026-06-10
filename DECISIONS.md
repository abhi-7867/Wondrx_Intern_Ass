# Decision Log (DECISIONS.md)

This document records the core architectural and concurrency design decisions made for the **Idempotent Payment / Wallet Event Processor**, along with an engineering analysis of suggestions evaluated during development.

---

## 1. How did you handle the concurrency race condition?

### The Core Problem
In a payment ledger system, duplicate or rapid concurrent webhook events can cause two critical concurrency hazards:
1. **Double-Spending / Balance Inconsistency (Lost Updates)**:
   When multiple debit requests for the same wallet hit the server concurrently, threads reading the balance at the same time might both see sufficient funds (e.g., balance = ₹500, two requests of ₹400). Both threads would deduct and save, leaving an illegal negative balance or losing one of the debits.
2. **Double Ingestion (Duplicate Webhook Delivery)**:
   When network latency or gateway retries cause the exact same `transactionId` to be delivered multiple times within milliseconds.

### The Solution Strategy: Multi-Layered Database-Level Concurrency Control

Rather than relying on in-memory locks (like Java `synchronized` or `ReentrantLock`), which fail in horizontally scaled multi-node microservices, concurrency control was moved to the **database tier**:

#### A. Database-Level Pessimistic Locking (`SELECT ... FOR UPDATE`)
- In `WalletRepository`, the method `findByUserIdWithLock` is annotated with `@Lock(LockModeType.PESSIMISTIC_WRITE)`.
- When invoked inside an active `@Transactional` boundary, Hibernate executes:
  ```sql
  SELECT * FROM wallets WHERE user_id = ? FOR UPDATE;
  ```
- **Execution Mechanism**:
  - The first transaction to execute this query obtains an exclusive row-level lock on that user's wallet record in the database.
  - Subsequent concurrent requests attempting to read or update the same user's wallet are placed in a queue by the database engine until the active transaction either commits or rolls back.
  - This serializes wallet balance checks and mutations on a per-user level.
  - When the balance is insufficient (`wallet.getBalance() < requestedAmount`), an `InsufficientFundsException` is thrown, immediately aborting the operation and preventing any negative balance.

#### B. Unique Database Constraints & Two-Phase Idempotency Check
- Webhook duplicate deliveries with the same `transactionId` are mitigated using a two-stage approach:
  1. **Pre-Lock Filter**: An early `existsById(transactionId)` query quickly rejects known duplicates before acquiring locks.
  2. **In-Lock Verification & Unique Constraint**: Once the wallet row lock is held, a second check guarantees that any concurrent thread waiting on the lock immediately detects if the previous thread just committed that transaction.
  3. **Primary Key / Unique Constraint**: The `transaction_id` column in the `transactions` table has a primary key / unique constraint. If concurrent requests somehow bypass in-flight checks (e.g., targeting different users with corrupted identical IDs), the database raises a unique constraint violation (`DataIntegrityViolationException`), which the `@RestControllerAdvice` converts to an HTTP `409 Conflict`.

---

## 2. Where did your AI assistant give you an incorrect or sub-optimal suggestion?

During initial design discussions, the AI assistant made several suggestions that were either sub-optimal or dangerous in production-grade financial systems:

### 1. In-Memory Java Synchronization (`synchronized` / `ConcurrentHashMap`)
- **AI Suggestion**: The assistant initially suggested wrapping the debit method with Java's `synchronized` keyword or using a `ConcurrentHashMap<UUID, Object>` to lock requests by `userId` in memory.
- **Why It Was Sub-optimal / Incorrect**:
  - **Single JVM Limitation**: In-memory locks only work inside a single JVM process. In a real cloud/microservice deployment where multiple instances run behind a load balancer, two concurrent requests routed to separate pods would completely bypass in-memory synchronization.
  - **Memory Leaks**: Maintaining dynamic locks per user in a `ConcurrentHashMap` without manual eviction leads to memory leaks under millions of unique users.
  - **Decision Taken**: Rejected in favor of **Database-Level Pessimistic Locking (`PESSIMISTIC_WRITE`)**, which works reliably across multiple threads and cluster instances.

### 2. Relying Solely on Optimistic Locking (`@Version`)
- **AI Suggestion**: The assistant proposed using JPA `@Version` column (optimistic locking) to detect concurrent modifications.
- **Why It Was Sub-optimal**:
  - In a burst scenario (such as 10 concurrent debits arriving within 10 milliseconds), optimistic locking allows all 10 transactions to read the balance simultaneously and only lets the first thread commit. The other 9 threads fail with an `OptimisticLockException` rather than processing eligible transactions in orderly sequence.
  - This would reject legitimate transactions that *could* have succeeded if queued sequentially.
  - **Decision Taken**: Used **Pessimistic Locking** (`SELECT ... FOR UPDATE`) so valid debits queue and execute cleanly until funds are genuinely depleted.

### 3. Catching `DataIntegrityViolationException` Inside the Service Without Transaction Awareness
- **AI Suggestion**: The assistant suggested placing a `try-catch` block around `transactionRepository.save()` inside the `@Transactional` service method and returning a 409 response object directly.
- **Why It Was Incorrect**:
  - In Spring Data JPA with Hibernate, once a database constraint fails, Hibernate marks the current persistence context / transaction as **Rollback-Only**.
  - Catching `DataIntegrityViolationException` inside the same `@Transactional` method and returning normally causes Spring to throw `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only` when the method exits.
  - **Decision Taken**: Allowed Spring's transaction boundary to roll back cleanly and handled `DataIntegrityViolationException` and custom `DuplicateTransactionException` in `@RestControllerAdvice`, ensuring correct HTTP 409 status and clean transaction cleanup.
