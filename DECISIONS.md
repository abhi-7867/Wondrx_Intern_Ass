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

