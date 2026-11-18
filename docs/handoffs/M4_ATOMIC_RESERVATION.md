# Member 4 Handoff — Complete Atomic Stock Reservation Integration (Issue #10)

- **Owner:** Member 4 (@Yasiru-ruwantha — Yasiru Ruwantha)
- **Role:** PostgreSQL Transactions and Concurrency
- **Module:** `backend/network-server` (`lk.thogamura.networkserver.inventory`, `...persistence`, `...transport.tcp`, `...concurrent`)
- **Status:** Complete (Fully verified with real Java TCP sockets, Java NIO notification server, and real PostgreSQL 17)
- **Reference Issue:** [#10 — [M4][W2] Atomic stock reservation with durable command receipts](https://github.com/Suthankan1/ThogaMura/issues/10) (Refs #10)
- **Related PRs:** PR #49 (Slice 10a: core transactions), PR #48 (Member 1: TCP transport)
- **Contracts:** [NETWORK_PROTOCOL_V1.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/contracts/NETWORK_PROTOCOL_V1.md), [BACKEND_PLAN.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/BACKEND_PLAN.md)
- **Primary Evidence File:** [reports/evidence/m4_atomic_reservation_evidence.txt](file:///Users/suthankan/Desktop/Projects/ThogaMura/reports/evidence/m4_atomic_reservation_evidence.txt)

---

## 1. Executive Summary & Flow

Issue #10 connects the frozen `REQUEST_STOCK` command across real Java TCP sockets to the PostgreSQL atomic reservation service and Member 3's Java NIO notification publisher.

```text
Gateway TCP client (java.net.Socket)
       ↓
Warehouse TCP server (TcpCommandServer)
       ↓
Command dispatcher (DefaultCommandDispatcher)
       ↓
Command adapter (StockReservationCommandHandler)
       ↓
Atomic reservation service (DefaultStockReservationService)
       ↓
PostgreSQL transaction (SELECT ... FOR UPDATE on product_stock)
       ↓
Durable result (command_receipts & stock_requests)
       ↓
TCP response (ACCEPTED / REJECTED / DUPLICATE_ID_CONFLICT)
       ↓ (strictly post-commit via TransactionSynchronization.afterCommit)
NIO publisher (NioNotificationServer -> {"v":1,"type":"CHANGED","version":<n>}\n)
```

In strict compliance with Assignment 02 and project rules:
1. **No Mocked Server State or Transports in Production Path:** Real Java blocking TCP sockets, real non-blocking Java NIO channels (`Selector`, `ServerSocketChannel`, `SocketChannel`), and real PostgreSQL 17 transactions.
2. **Fundamental Invariant Enforced:**
   $$\text{available} = \text{on\_hand} - \text{reserved} \ge 0$$
   Relational check constraint `ck_reserved_not_over_on_hand` guarantees available stock never drops below zero.
3. **Pessimistic Row-Level Locking:** Uses `SELECT sku, name, on_hand, reserved FROM product_stock WHERE sku = ? FOR UPDATE` inside `READ_COMMITTED` transactions to strictly serialize concurrent requests on the same SKU without deadlock or phantom anomalies.
4. **Durable Idempotency & Replay:** In the exact same database transaction that updates `product_stock` and inserts `stock_requests`, a SHA-256 canonical payload fingerprint and wire response JSON are persisted to `command_receipts`.
   - Matching `requestId` + matching payload fingerprint: Replays original outcome identically (`replayed = true`), without mutating inventory or firing notifications.
   - Matching `requestId` + conflicting payload fingerprint: Returns `DUPLICATE_ID_CONFLICT` error without modifying database records.
5. **Java NIO Notification Publication Boundary:** Cache invalidation signals are published strictly **after** transaction commit via Spring `TransactionSynchronizationManager.registerSynchronization(afterCommit)`. Best-effort notification failures are logged as warnings and never roll back committed transactions. Replays and rejections never emit notifications.

---

## 2. Exact Files Implemented

### Production Code (`backend/network-server/src/main/java/lk/thogamura/networkserver/`)

| File | Package | Responsibility |
| :--- | :--- | :--- |
| `StockReservationCommandHandler.java` | `inventory` | Command handler adapting `REQUEST_STOCK` wire frames to `StockReservationService`. Validates canonical UUID `requestId`, non-blank `sku`, and positive integer `quantity`. |
| `DefaultCommandDispatcher.java` | `transport.tcp` | Spring `@Component` auto-registering `StockReservationCommandHandler` for `REQUEST_STOCK` when `StockReservationService` is present. |
| `StockReservationService.java` | `inventory` | Public service interface for transactional stock reservation. |
| `DefaultStockReservationService.java` | `inventory` | Transactional core service coordinating row locks, reservation, receipts, and NIO events. |
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
| `InventoryWorkerPool.java` | `concurrent` | Bounded thread pool executor (`inventory-worker-%d`) with backpressure control. |
| `WorkerPoolConfig.java` | `concurrent` | Spring configuration declaring the `InventoryWorkerPool` bean. |

### Test Code (`backend/network-server/src/test/java/lk/thogamura/networkserver/`)

| File | Type | Responsibility |
| :--- | :--- | :--- |
| `StockReservationCommandHandlerTest.java` | Unit Test (6 tests) | Verifies command field extraction, missing data, blank SKU, non-integer/negative quantity validation, and dispatcher registration. |
| `TcpAtomicReservationSocketIntegrationTest.java` | Integration Test (6 tests) | Real TCP sockets & real PostgreSQL 17 integration testing: ACCEPTED, REJECTED, idempotent same-ID replay, `DUPLICATE_ID_CONFLICT`, concurrent socket clients preventing oversubscription, and server restart durability. |
| `PostCommitNioNotificationIntegrationTest.java` | Integration Test (5 tests) | Verifies Java NIO notification publisher (`NioNotificationServer`) receives version-only notifications strictly after commit, zero events on replay or rejection, zero leaked payload fields, and best-effort tolerance when NIO server stops. |
| `AtomicStockReservationIntegrationTest.java` | Integration Test (14 tests) | Core database isolation and transaction verification. |
| `PayloadFingerprintCalculatorTest.java` | Unit Test (5 tests) | Verifies canonical SHA-256 computation, whitespace trimming, and key order invariance. |
| `InventoryWorkerPoolTest.java` | Concurrency Test (3 tests) | Verifies named worker threads, queue bounding, and clean shutdown lifecycle. |
| `PostgresTestHelper.java` | Shared Helper | Centralized PostgreSQL datasource, Flyway migration, and transaction manager runner. |

---

## 3. Real Socket & PostgreSQL 17 Execution Results

Targeted test execution:
```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn -f backend/network-server/pom.xml test \
  -Dtest=TcpAtomicReservationSocketIntegrationTest,PostCommitNioNotificationIntegrationTest,StockReservationCommandHandlerTest
```

### Output Summary:
```text
[INFO] Running lk.thogamura.networkserver.inventory.PostCommitNioNotificationIntegrationTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.215 s
[INFO] Running lk.thogamura.networkserver.inventory.StockReservationCommandHandlerTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.124 s
[INFO] Running lk.thogamura.networkserver.inventory.TcpAtomicReservationSocketIntegrationTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.432 s
[INFO] 
[INFO] Results:
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Full backend test suite:
```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn -f backend/pom.xml clean verify
```
- `backend/protocol`: 21 tests, 0 failures
- `backend/network-server`: 75 tests (TCP sockets + NIO + PostgreSQL transactions + schema constraints), 0 failures
- `backend/api-gateway`: 61 tests, 0 failures
- **Total Backend Tests:** **157 automated tests passing cleanly with 0 failures, 0 errors, and 0 skipped.**

---

## 4. Verification of Acceptance Criteria

All mandated integration criteria from Issue #10 are verified:

1. **Request 8 from 10 Available over TCP:**
   - TCP client sends `REQUEST_STOCK` frame with `requestId`, `sku: "ITEM-007"`, `quantity: 8`.
   - Wire response: `status: "ACCEPTED"`.
   - PostgreSQL: `reserved = 8, available = 2`, `command_receipts` records outcome.
2. **Request Exceeding Available Stock over TCP:**
   - Client sends `quantity: 5` (available is 2).
   - Wire response: `status: "REJECTED"`, `error.code: "INSUFFICIENT_STOCK"`.
   - PostgreSQL: `reserved = 8, available = 2` (untouched), durable receipt records `REJECTED`.
3. **Durable Replay with Same Request ID & Payload over TCP:**
   - Resending identical request returns original `ACCEPTED` receipt without re-executing inventory calculations.
   - PostgreSQL balances remain `reserved = 8, available = 2` (never overcounted to 16).
4. **Duplicate Request ID with Different Payload over TCP:**
   - Resending same `requestId` with different quantity or SKU returns `status: "ERROR"`, `error.code: "DUPLICATE_ID_CONFLICT"`.
5. **Concurrent TCP Socket Clients Cannot Oversubscribe Stock:**
   - 20 concurrent threads open independent TCP sockets to `TcpCommandServer`, each requesting 1 unit against 10 available units.
   - Exactly 10 clients receive `ACCEPTED`, exactly 10 clients receive `REJECTED`.
   - PostgreSQL check constraint `ck_reserved_not_over_on_hand` holds; final balances are `on_hand = 10, reserved = 10, available = 0`.
6. **Server Restart Preserves Receipts:**
   - `TcpCommandServer` instance stopped and restarted on a new port with a fresh dispatcher.
   - Sending original `requestId` over TCP to restarted server correctly replays committed receipt from PostgreSQL.
7. **Post-Commit Java NIO Notification Feed:**
   - Committed stock reservation produces single-line version-only frame: `{"v":1,"type":"CHANGED","version":101}\n` on port 7444.
   - Frame strictly obeys privacy contract: zero leaked SKUs, quantities, customer details, or IDs.
   - Replay, rejection, and conflict operations do not produce false new change notifications.
   - If NIO notification server is stopped, TCP reservation transaction safely commits to PostgreSQL (best-effort resilience).

---

## 5. Next Steps

- Keep PR open for team review and merge targeting `main`.
- With Issue #10 connected end-to-end, API Gateway can invoke `REQUEST_STOCK` across the real TCP command transport.
