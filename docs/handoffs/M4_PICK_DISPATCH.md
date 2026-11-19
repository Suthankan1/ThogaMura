# Member 4 Handoff — PICKED and DISPATCHED Transitions with Single Stock Movement (Issue #21)

- **Owner:** Member 4 (@Yasiru-ruwantha — Yasiru Ruwantha)
- **Role:** PostgreSQL Transactions and Concurrency
- **Module:** `backend/network-server` (`lk.thogamura.networkserver.fulfillment`, `...persistence`, `...transport.tcp`, `...inventory`)
- **Status:** Complete (Implemented & Verified with real PostgreSQL 17 and real Java TCP sockets)
- **Reference Issue:** [#21 — [M4][W3] PICKED and DISPATCHED transitions with one stock movement](https://github.com/Suthankan1/ThogaMura/issues/21) (Refs #21)
- **Related PRs:** PR #49 (Issue #10 core transactions), PR #52 (Issue #10 TCP integration), PR #54 (Issue #12 worker pool concurrency)
- **Contracts:** [NETWORK_PROTOCOL_V1.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/contracts/NETWORK_PROTOCOL_V1.md), [BACKEND_PLAN.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/BACKEND_PLAN.md)

---

## 1. Architecture & Fulfillment Flow

Issue #21 completes the fulfillment transaction lifecycle specified in `NETWORK_PROTOCOL_V1.md`:
$$\text{ACCEPTED} \longrightarrow \text{PICKED} \longrightarrow \text{DISPATCHED}$$

```text
Gateway TCP Client (java.net.Socket)
       ↓
Warehouse TCP Server (TcpCommandServer)
       ↓
Command Dispatcher (DefaultCommandDispatcher)
       ↓
Fulfillment Command Handler (StockFulfillmentCommandHandler)
       ↓
Stock Fulfillment Service (DefaultStockFulfillmentService)
       ↓
PostgreSQL 17 Transaction (SELECT ... FOR UPDATE on stock_requests & product_stock)
       ├── Status update: ACCEPTED -> PICKED -> DISPATCHED
       ├── Atomic balance decrement: on_hand -= qty, reserved -= qty (available unchanged)
       ├── Stock movement insertion: exactly 1 record (quantity_delta = -qty, reason = 'DISPATCH')
       └── Durable command receipt: SHA-256 fingerprint + wire response JSON
       ↓ (strictly post-commit via TransactionSynchronization.afterCommit)
NIO Publisher (NioNotificationServer -> {"v":1,"type":"CHANGED","version":<n>}\n)
       ↓
TCP Response (Wire Response JSON)
```

### Invariants Enforced
1. **Valid State Transitions:**
   - `ACCEPTED -> PICKED`: Allowed.
   - `PICKED -> DISPATCHED`: Allowed.
   - Any other transition (e.g. `ACCEPTED -> DISPATCHED`, `DISPATCHED -> PICKED`, `REJECTED -> PICKED`) is rejected deterministically with `INVALID_TRANSITION` and zero database changes.
2. **Atomic Dispatch & Movement:**
   - On transition to `DISPATCHED`, `on_hand` and `reserved` are both decremented by `quantity` inside the same database transaction.
   - Preserves $\text{available} = \text{on\_hand} - \text{reserved}$.
   - Exactly one row is inserted into `stock_movements` with `quantity_delta = -quantity` and `reason = 'DISPATCH'`. The database unique constraint on `stock_request_id` enforces that no request can generate multiple movements.
3. **Durable Idempotency & Replay:**
   - SHA-256 canonical payload fingerprints are computed for fulfillment commands.
   - Replaying the identical `requestId` and payload returns the cached response with `replayed: true` without re-executing inventory decrements or creating duplicate movements.
   - Reusing a `requestId` with a differing payload returns `DUPLICATE_ID_CONFLICT`.
4. **Post-Commit NIO Notification:**
   - Notifications are dispatched strictly after successful database transaction commit via Spring's `TransactionSynchronizationManager.registerSynchronization(afterCommit)`.
   - Notification broadcaster errors are logged as warnings and never roll back committed transactions. Replays and failed transitions never emit notifications.

---

## 2. Key Classes & Implementations

1. **Persistence Layer (`backend/network-server/src/main/java/lk/thogamura/networkserver/persistence/`):**
   - `StockMovementRecord.java`: Immutable domain record matching the PostgreSQL `stock_movements` schema.
   - `StockMovementRepository.java` & `JdbcStockMovementRepository.java`: Insert and query methods (`insert`, `findByStockRequestId`, `findBySku`, `findAll`).
   - `StockRequestRepository.java` & `JdbcStockRequestRepository.java`: Added `findByIdForUpdate` (row-level locking), `updateStatus`, `findAll`, `findByStatus`.
   - `ProductStockRepository.java` & `JdbcProductStockRepository.java`: Added `updateBalances` to decrement `on_hand` and `reserved` atomically.
   - `PayloadFingerprintCalculator.java`: Added `calculateStockRequestIdFingerprint` for `MARK_PICKED` and `MARK_DISPATCHED`.

2. **Fulfillment Service (`backend/network-server/src/main/java/lk/thogamura/networkserver/fulfillment/`):**
   - `StockFulfillmentService.java`: Interface defining `markPicked`, `markDispatched`, `getRequests`, `getMovements`.
   - `DefaultStockFulfillmentService.java`: Transactional implementation managing row-level locks, transitions, stock decrements, movements, command receipts, and post-commit NIO notifications.
   - `FulfillmentResult.java`: Result DTO encapsulating outcome, receipt status, stock request, and movement.

3. **Command Transport Integration (`backend/network-server/src/main/java/lk/thogamura/networkserver/transport/tcp/`):**
   - `StockFulfillmentCommandHandler.java`: Handles `MARK_PICKED`, `MARK_DISPATCHED`, `GET_REQUESTS`, and `GET_MOVEMENTS` TCP commands according to protocol specifications.
   - `DefaultCommandDispatcher.java`: Dispatches fulfillment and inventory commands, with backward-compatible constructors.

---

## 3. Automated Verification

### Targeted Unit & Integration Tests
- `StockFulfillmentCommandHandlerTest.java` (6 tests):
  - `shouldHandleMarkPickedSuccessfully`: Decodes frame, validates request, returns wire format.
  - `shouldHandleMarkDispatchedSuccessfully`: Encodes movement and status.
  - `shouldReturnInvalidTransitionError`: Encodes error payload.
  - `shouldReturnDuplicateIdConflictError`: Conflicting payload handling.
  - `shouldHandleGetRequestsQuery`: Serializes request array.
  - `shouldHandleGetMovementsQuery`: Serializes movement array.

- `StockFulfillmentIntegrationTest.java` (11 tests with real PostgreSQL 17 & real blocking TCP sockets):
  1. Complete lifecycle: `ACCEPTED -> PICKED -> DISPATCHED` atomically updates stock and creates one movement.
  2. Replaying identical pick or dispatch returns recorded outcome without duplicate mutation (`replayed = true`).
  3. Reusing command `requestId` with different `stockRequestId` returns `DUPLICATE_ID_CONFLICT`.
  4. Attempting to dispatch an unpicked request returns `INVALID_TRANSITION` and preserves DB state.
  5. Attempting to pick an already dispatched request returns `INVALID_TRANSITION`.
  6. Requesting non-existent `stockRequestId` returns `NOT_FOUND`.
  7. Concurrent dispatch requests on same stock request serialize: exactly 1 succeeds, 1 rejected.
  8. Failure of NIO notification broadcaster never rolls back committed dispatch transaction.
  9. `GET_REQUESTS` and `GET_MOVEMENTS` return accurate query results over TCP.
  10. Valid pick and dispatch outcomes persist across server restart.
  11. Transaction rollback atomicity on dispatch error: zero residual movements or stock decrements.

### Full Reactor Verification
All 194 automated tests across all 4 modules passed with 0 failures, 0 errors:
- `backend/protocol`: 21 tests
- `backend/network-server`: 92 tests
- `backend/api-gateway`: 81 tests
