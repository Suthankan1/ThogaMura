# Member 4 Handoff — Atomic Stock Reservation Core with Durable Receipts (Issue #10 Slice 10a)

- **Owner:** Member 4 (@Yasiru-ruwantha — Yasiru Ruwantha)
- **Role:** PostgreSQL Transactions and Concurrency
- **Module:** `backend/network-server` (`lk.thogamura.networkserver.inventory`, `...persistence`, `...concurrent`)
- **Status:** Complete (Fully verified with real PostgreSQL 17.11 & Docker)
- **Reference Issue:** [#10 — [M4][W2] Atomic stock reservation with durable command receipts](https://github.com/Suthankan1/ThogaMura/issues/10) (Refs #10)
- **Related Issues:** Issue #8 (Schema complete), Issue #7 (TCP command transport in progress by Member 1)
- **Contracts:** [NETWORK_PROTOCOL_V1.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/contracts/NETWORK_PROTOCOL_V1.md), [BACKEND_PLAN.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/BACKEND_PLAN.md)
- **Primary Evidence File:** [reports/evidence/m4_atomic_reservation_evidence.txt](file:///Users/suthankan/Desktop/Projects/ThogaMura/reports/evidence/m4_atomic_reservation_evidence.txt)

---

## 1. Executive Summary & Architecture

This milestone implements the core transactional engine for **ThogaMura** inventory reservations.
In strict compliance with Assignment 02 and project rules:
1. **No Mocked State in Production Path:** Uses pure Spring JDBC and PostgreSQL 17 transactions.
2. **Fundamental Invariant Enforced:**
   $$\text{available} = \text{on\_hand} - \text{reserved} \ge 0$$
   PostgreSQL relational check constraint `ck_reserved_not_over_on_hand` guarantees availability never drops below zero.
3. **Pessimistic Row-Level Locking:** Uses `SELECT sku, name, on_hand, reserved FROM product_stock WHERE sku = ? FOR UPDATE` inside `READ_COMMITTED` transactions to strictly serialize concurrent requests on the same SKU without deadlock or phantom anomalies.
4. **Durable Idempotency & Replay:** In the exact same database transaction that updates `product_stock` and inserts `stock_requests`, a SHA-256 canonical payload fingerprint and wire response JSON are persisted to `command_receipts`.
   - Matching `requestId` + matching payload fingerprint: Replays original outcome identically (`replayed = true`), without mutating inventory or firing notifications.
   - Matching `requestId` + conflicting payload fingerprint: Returns `DUPLICATE_ID_CONFLICT` error without modifying database records.
5. **Java NIO Notification Publication Boundary:** Cache invalidation signals are published strictly **after** transaction commit via Spring `TransactionSynchronizationManager.registerSynchronization(afterCommit)`. Best-effort notification failures are logged as warnings and never roll back committed transactions.

---

## 2. Exact Files Implemented

### Production Code (`backend/network-server/src/main/java/lk/thogamura/networkserver/`)

| File | Package | Responsibility |
| :--- | :--- | :--- |
| `ProductStock.java` | `persistence` | Immutable entity record for `product_stock` enforcing `0 <= reserved <= on_hand`. |
| `ProductStockRepository.java` | `persistence` | Repository interface defining row locking `findBySkuForUpdate` and updates. |
| `JdbcProductStockRepository.java` | `persistence` | Spring JDBC implementation using PostgreSQL `SELECT ... FOR UPDATE`. |
| `StockRequestRecord.java` | `persistence` | Immutable entity record for `stock_requests` with status and fingerprint. |
| `StockRequestRepository.java` | `persistence` | Repository interface for stock request queue and lifecycle tracking. |
| `JdbcStockRequestRepository.java` | `persistence` | Spring JDBC implementation for `stock_requests`. |
| `CommandReceipt.java` | `persistence` | Entity record for durable idempotency receipts in `command_receipts`. |
| `CommandReceiptRepository.java` | `persistence` | Repository interface for durable receipts. |
| `JdbcCommandReceiptRepository.java` | `persistence` | Spring JDBC implementation storing JSONB payloads with `?::jsonb`. |
| `PayloadFingerprintCalculator.java` | `inventory` | SHA-256 canonical payload fingerprint calculator for normalized JSON envelopes. |
| `ReservationCommand.java` | `inventory` | Inbound command value object (`requestId`, `sku`, `quantity`). |
| `ReservationResult.java` | `inventory` | Authoritative result value object containing canonical wire response JSON. |
| `MonotonicVersionProvider.java` | `inventory` | Interface for monotonically increasing notification version numbers. |
| `DefaultMonotonicVersionProvider.java` | `inventory` | `AtomicLong` implementation seeded from system clock for restart monotonicity. |
| `StockReservationService.java` | `inventory` | Public service interface for Member 1 dispatcher and test invocation. |
| `DefaultStockReservationService.java` | `inventory` | Transactional core service coordinating row locks, reservation, receipts, and NIO events. |
| `InventoryWorkerPool.java` | `concurrent` | Bounded thread pool executor (`inventory-worker-%d`) with backpressure control. |
| `WorkerPoolConfig.java` | `concurrent` | Spring configuration declaring the `InventoryWorkerPool` bean. |

### Test Code (`backend/network-server/src/test/java/lk/thogamura/networkserver/`)

| File | Type | Responsibility |
| :--- | :--- | :--- |
| `PostgresTestHelper.java` | Shared Helper | Centralized PostgreSQL datasource, Flyway migration, and transaction manager runner. |
| `PayloadFingerprintCalculatorTest.java` | Unit Test (5 tests) | Verifies canonical SHA-256 computation, whitespace trimming, and key order invariance. |
| `InventoryWorkerPoolTest.java` | Concurrency Test (3 tests) | Verifies named worker threads, queue bounding, and clean shutdown lifecycle. |
| `AtomicStockReservationIntegrationTest.java` | Integration Test (14 tests) | Comprehensive real PostgreSQL 17 test suite covering all 9 prompt requirements. |

---

## 3. Real PostgreSQL 17 Execution Results

Executed against PostgreSQL 17.11:

```bash
mvn -f backend/network-server/pom.xml test -Dtest=AtomicStockReservationIntegrationTest,PayloadFingerprintCalculatorTest,InventoryWorkerPoolTest
```

### Output Summary:
```text
[INFO] Running lk.thogamura.networkserver.inventory.AtomicStockReservationIntegrationTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.220 s
[INFO] Running lk.thogamura.networkserver.inventory.PayloadFingerprintCalculatorTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s
[INFO] Running lk.thogamura.networkserver.concurrent.InventoryWorkerPoolTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.004 s
[INFO] 
[INFO] Results:
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Full backend suite (`mvn -f backend/pom.xml clean verify`):
- `backend/protocol`: 21 tests, 0 failures
- `backend/network-server`: 58 tests (schema constraints + atomic reservation + NIO server + concurrency), 0 failures
- `backend/api-gateway`: 43 tests, 0 failures
- **Total Backend Tests:** **122 automated tests passing cleanly.**

---

## 4. Verification of Prompt Requirements & Negative Cases

All 9 prompt-mandated test scenarios are implemented and passing in `AtomicStockReservationIntegrationTest`:

1. **Request 8 from 10 Available:**
   - Result: `ACCEPTED`, `reserved` increments from 0 to 8, `available` drops to 2.
   - Persisted rows: `stock_requests` has `status='ACCEPTED'`, `command_receipts` has JSON with `"status":"ACCEPTED"`.
2. **Request Above Available Quantity Rejected:**
   - Request 8 when available is 2: `REJECTED`, `errorCode='INSUFFICIENT_STOCK'`.
   - Persisted rows: `product_stock` unchanged (`reserved=8`), `stock_requests` records `status='REJECTED'`, `command_receipts` records rejection JSON for replay.
3. **Database Balances Invariant:**
   - Multi-step reservations (3, then 5, then 2, then try 1): `available = on_hand - reserved >= 0` verified at every step.
4. **Two Concurrent Requests Cannot Oversubscribe Stock:**
   - Two parallel threads submit requests for 8 units concurrently against 10 available using `InventoryWorkerPool` and `CountDownLatch`.
   - Exactly one is `ACCEPTED`, exactly one is `REJECTED`. Final database state: `reserved=8, available=2` (never 16).
5. **Deterministic Replay on Duplicate Request ID:**
   - Replay of `ACCEPTED` request returns identical JSON without changing stock.
   - Replay of `REJECTED` request returns identical JSON without changing stock.
6. **Different Payload with Same Request ID Conflicts:**
   - Reusing `requestId` with different quantity or different SKU returns `status: "ERROR"`, `errorCode: "DUPLICATE_ID_CONFLICT"`. Stored receipt remains intact.
7. **Server Restart Preserves Durable Receipts:**
   - Application context re-instantiated with fresh repository instances; retrying original `requestId` loads persisted PostgreSQL receipt and replays outcome.
8. **Rollback Atomicity:**
   - Deliberate exception during transaction rolls back `product_stock` update and inserts cleanly; zero residual rows survive.
9. **Concurrent Duplicate Delivery Idempotency:**
   - 10 threads concurrently deliver the exact same `(requestId, SKU, 8)`.
   - All 10 threads return `ACCEPTED` with matching JSON. PostgreSQL reserves exactly 8 units (not 80), with exactly 1 row in `stock_requests` and 1 in `command_receipts`.
10. **NIO Publication Boundary:**
    - Change event published strictly **after** transaction commit; zero events published on replay or rejection.
11. **Fault-Tolerant NIO Notification:**
    - If `NotificationPublisher` throws an exception, the exception is logged as a warning; the stock reservation remains safely committed in PostgreSQL.
12. **Non-Existent SKU (`NOT_FOUND`):**
    - Requesting unknown SKU returns `status: "ERROR"`, `errorCode: "NOT_FOUND"`.
13. **Validation Errors (`VALIDATION_ERROR`):**
    - Quantities $\le 0$ or blank SKUs return `VALIDATION_ERROR`.
14. **Raw JSON Framing:**
    - Validated with complete newline-delimited Protocol v1 JSON frames.

---

## 5. How Member 1's Dispatcher Will Invoke This Service

Member 1 (`@sankharavindu`) is implementing the Java TLS/TCP command dispatcher (Issue #7). When a `REQUEST_STOCK` frame arrives on port 7443, Member 1 can invoke Member 4's service in either of three ways:

### Option A: Direct Method Invocation (Recommended)
```java
@Autowired
private StockReservationService stockReservationService;

// Inside Member 1's socket command handler:
UUID requestId = UUID.fromString(requestEnvelope.get("requestId").asText());
String sku = requestEnvelope.path("data").path("sku").asText();
int quantity = requestEnvelope.path("data").path("quantity").asInt();

ReservationResult result = stockReservationService.reserveStock(requestId, sku, quantity);

// Write result directly back to the TLS socket:
tcpFrameWriter.writeFrame(socket.getOutputStream(), result.responseJson());
```

### Option B: Raw Frame Pass-Through
```java
// Pass entire raw JSON frame received from socket:
ReservationResult result = stockReservationService.processReservationCommand(rawFrameJson);
tcpFrameWriter.writeFrame(socket.getOutputStream(), result.responseJson());
```

---

## 6. Remaining Dependencies & Next Steps

1. **Issue #7 Integration (Member 1):** Connect `StockReservationService` to the TLS/TCP socket listener in `lk.thogamura.networkserver.transport.tcp`.
2. **Issue #21 State Transitions (Member 4 - Week 3):** Implement `MARK_PICKED`, `MARK_DISPATCHED`, and append-only `stock_movements` ledger transitions.
3. **Issue #10 Completion:** Keep Issue #10 open until Issue #7 socket transport integrates end-to-end and browser-to-server reservation is verified.
