# Member 4 Handoff — PostgreSQL Schema, Constraints, and Seed Fixtures (Issue #8)

- **Owner:** Member 4 (@Yasiru-ruwantha — Yasiru Ruwantha)
- **Role:** PostgreSQL Transactions and Concurrency
- **Module:** `backend/network-server` (`lk.thogamura.networkserver.persistence`, `lk.thogamura.networkserver.inventory`)
- **Status:** Complete (Verified with real PostgreSQL 17)
- **Reference Issue:** [#8 — [M4][W1] Verify PostgreSQL schema, constraints and synthetic seed data](https://github.com/Suthankan1/ThogaMura/issues/8)
- **Contracts:** [NETWORK_PROTOCOL_V1.md](../contracts/NETWORK_PROTOCOL_V1.md) & [V1__initial_schema.sql](../../backend/network-server/src/main/resources/db/migration/V1__initial_schema.sql)

---

## 1. Schema Explanation & Invariants

PostgreSQL 17 serves as the single authoritative persistence tier for warehouse inventory, state transitions, idempotency receipts, and historical movements. Only the `network-server` process interacts directly with PostgreSQL.

### Core Inventory Invariant:
$$\text{available} = \text{on\_hand} - \text{reserved}$$
$$\text{on\_hand} \ge 0, \quad \text{reserved} \ge 0, \quad \text{available} \ge 0 \implies \text{reserved} \le \text{on\_hand}$$

This fundamental business invariant is not left to application-level Java checks alone; it is enforced directly by PostgreSQL relational constraints at the database engine level.

---

## 2. Table Design & Relational Constraints

### 1. `product_stock`
- **Primary Key:** `sku TEXT PRIMARY KEY`
- **Fields:** `name TEXT NOT NULL`, `on_hand INTEGER NOT NULL`, `reserved INTEGER NOT NULL DEFAULT 0`
- **Check Constraints:**
  - `CHECK (on_hand >= 0)`
  - `CHECK (reserved >= 0)`
  - `CONSTRAINT ck_reserved_not_over_on_hand CHECK (reserved <= on_hand)`
- **Invariant Guarantee:** Guarantees `available >= 0` under all concurrency scenarios.

### 2. `stock_requests`
- **Primary Key:** `request_id UUID PRIMARY KEY`
- **Foreign Key:** `sku REFERENCES product_stock (sku)`
- **Check Constraints:**
  - `CHECK (quantity > 0)`
  - `CHECK (status IN ('ACCEPTED', 'REJECTED', 'PICKED', 'DISPATCHED'))`
- **Index:** `idx_stock_requests_status_created` on `(status, created_at)` for fast queue polling.

### 3. `command_receipts`
- **Primary Key:** `command_request_id UUID PRIMARY KEY`
- **Fields:** `command_type TEXT NOT NULL`, `payload_fingerprint CHAR(64) NOT NULL`, `result_json JSONB NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- **Guarantee:** Persisted in the identical transaction as any stock mutation; ensures exact deduplication and deterministic replay for retried commands.

### 4. `stock_movements`
- **Primary Key:** `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`
- **Foreign Key:** `stock_request_id UUID NOT NULL UNIQUE REFERENCES stock_requests (request_id)`
- **Check Constraints:**
  - `CHECK (quantity_delta < 0)`
  - `CHECK (reason = 'DISPATCH')`
- **Guarantee:** Append-only ledger; exactly one dispatch movement per fulfilled request.

---

## 3. Synthetic Seed Fixtures

Created in `lk.thogamura.networkserver.inventory.SyntheticInventoryFixtures` and `backend/network-server/src/test/resources/fixtures/dev-seed-fixtures.sql`:

| SKU | Name | On hand | Reserved | Available |
| :--- | :--- | :---:| :---:| :---:|
| **`ITEM-007`** | Whole Ceylon Black Pepper | 10 | 0 | 10 |
| **`ITEM-008`** | Ceylon Cinnamon Quills | 0 | 0 | 0 |

### Key Properties:
1. **Idempotence:** Uses `INSERT ... ON CONFLICT (sku) DO UPDATE` to guarantee that executing fixture loading multiple times never causes duplicate primary key violations or corrupts state.
2. **Strict Isolation:** Restricted strictly to development and automated testing; production application bootstrap never inserts synthetic data automatically.

---

## 4. Database Integration Tests & Actual Results

Executed via `PostgresSchemaConstraintIntegrationTest` against real PostgreSQL 17:

```bash
mvn -f backend/network-server/pom.xml test -Dtest=PostgresSchemaConstraintIntegrationTest
```

### Actual Output:
```text
-------------------------------------------------------
 T E S T S
-------------------------------------------------------
Running lk.thogamura.networkserver.persistence.PostgresSchemaConstraintIntegrationTest
18:38:28.175 [main] INFO org.flywaydb.core.FlywayExecutor -- Database: jdbc:postgresql://localhost:5433/thogamura (PostgreSQL 17.11)
18:38:28.208 [main] INFO org.flywaydb.core.internal.command.DbValidate -- Successfully validated 1 migration
18:38:28.228 [main] INFO org.flywaydb.core.internal.command.DbMigrate -- Schema "public" is up to date. No migration necessary.
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.825 s

Results:
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Negative Constraint Test Cases Verified:
1. **Negative `on_hand` Rejection:** Attempting to insert `on_hand = -1` triggers `DataIntegrityViolationException` from PostgreSQL.
2. **Negative `reserved` Rejection:** Attempting to insert `reserved = -1` triggers `DataIntegrityViolationException`.
3. **Over-reservation Rejection (`reserved > on_hand`):** Attempting to insert `on_hand = 10, reserved = 11` triggers constraint violation `ck_reserved_not_over_on_hand`.
4. **Update Over-reservation Rejection:** Attempting to update `reserved` beyond current `on_hand` triggers constraint violation `ck_reserved_not_over_on_hand`.
5. **Primary Key Duplication:** Inserting duplicate SKU or `request_id` triggers unique constraint violation.
6. **Foreign Key Violation:** Inserting `stock_requests` with non-existent SKU or `stock_movements` with non-existent `stock_request_id` triggers FK violation.
7. **Negative or Zero Quantity Delta on Dispatch:** Inserting `quantity_delta >= 0` on `stock_movements` is rejected.
8. **Invalid Movement Reason:** Inserting reason other than `'DISPATCH'` is rejected.
9. **Duplicate Dispatch Movements:** Inserting more than 1 movement for the same request triggers `stock_movements_stock_request_id_key` unique violation.
10. **Committed Durability:** Committed records persist across independent queries and database restarts.

---

## 5. Remaining Limitations

1. **Transaction Management:** This issue validates schema constraints and fixture initialization. Atomic multi-row transactional reservations (`SELECT ... FOR UPDATE`, receipt insertion, atomic commit) belong to **Issue #10**.
2. **Isolation:** Tests use explicit table cleanup between test methods, ensuring deterministic, idempotent local runs without data pollution.
3. **Zero Secrets:** Local development credentials remain isolated in `.env` and environment variables.

## Stabilization handoff

Integration suites share a disposable PostgreSQL container and cannot reuse the application database. The demo fixture installs only into empty tables. Review stable fulfillment replay and transactional cancellation or receiving proposals before changing the schema.

## Workspace schema handoff

Review V3, the PostgreSQL migration and tied-cursor tests, and the cancel-versus-pick coverage in the protected smoke script. Cancellation holds the request and product locks through release and receipt commit. Receiving links one positive movement to one persisted command receipt, with the supervisor account and reference. [Workspace contract](../contracts/WORKSPACE_V2.md)
