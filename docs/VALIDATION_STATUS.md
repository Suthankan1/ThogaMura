# Starter validation status — 2026-11-15

This document distinguishes the **generated starter** from completed coursework features.

## Checked in artifact creation environment

- Frontend dependency lockfile created.
- `npm ci` completed successfully.
- `npm run typecheck` completed successfully.
- `npm run lint` completed successfully.
- `npm run build` completed successfully with static routes: `/`, `/products`, `/requests`, `/requests/new`, `/warehouse` and `/activity`.
- Java backend POM/XML and directory structure reviewed as source scaffold, but **Maven/JDK compiler unavailable in the packaging environment**; `mvn verify` was **not** run.
- Docker unavailable in the packaging environment; PostgreSQL and Flyway startup were **not** run.
- Real TCP/UDP/NIO/TLS integrations **do not exist yet** and have no green integration evidence.

## Member workstation verification — 2026-11-18 (Member 4 / Issue #3)

Environment validation executed by @Yasiru-ruwantha (Member 4 — PostgreSQL Transactions & Concurrency) on Apple Silicon macOS (aarch64):

1. **Tooling confirmed:**
   - **JDK:** OpenJDK `21.0.12.1` and `25.0.4.1` installed (`javac 21.0.12.1`).
   - **Maven:** Apache Maven `3.9.16`.
   - **Node.js & npm:** Node `v24.22.3`, npm `11.19.1`.
   - **Docker & Compose:** Docker `29.9.3`, Docker Compose `v5.7.0`.
   - **PostgreSQL Client:** `psql` (PostgreSQL) `18.x` available locally.
2. **Backend build & test:**
   - `mvn -f backend/pom.xml clean verify` executed with JDK 21:
   - 4 modules (`thogamura-backend`, `protocol`, `network-server`, `api-gateway`) built successfully (`BUILD SUCCESS`).
   - All 79 backend tests passed (36 in `network-server`, 43 in `api-gateway`, 0 failures, 0 errors).
3. **Frontend clean build:**
   - `npm --prefix frontend ci`: clean dependency install.
   - `npm --prefix frontend test`: 7/7 unit tests passed.
   - `npm --prefix frontend run typecheck`: clean, 0 TypeScript errors.
   - `npm --prefix frontend run lint`: clean, 0 ESLint errors.
   - `npm --prefix frontend run build`: production build passed with Turbopack, prerendering all 8 routes (`/`, `/_not-found`, `/activity`, `/products`, `/requests`, `/requests/new`, `/suppliers`, `/warehouse`).
4. **PostgreSQL & Flyway:**
   - Started PostgreSQL 17 Alpine using Docker Compose (`docker compose up -d db`). Configurable via `POSTGRES_PORT` in `.env` (defaulting to 5432, verified with 5433 to avoid host port collisions).
   - `thogamura-db-1` achieved `healthy` status via internal `pg_isready` healthcheck.
   - Executed `NetworkServerApplication` against real PostgreSQL container: HikariCP connection pool initialized, Flyway connected, created `flyway_schema_history`, and applied `V1__initial_schema.sql` (schema version 1).
   - Verified 5 tables created: `command_receipts`, `flyway_schema_history`, `product_stock`, `stock_movements`, `stock_requests`.
   - Verified data persistence across container restart (`docker compose restart db` preserved inserted `product_stock` record).
   - Re-running `NetworkServerApplication` validated schema idempotence (`Schema "public" is up to date. No migration necessary.`).
5. **Gateway metadata verification:**
   - Started `api-gateway` on port 8080 and curled `GET /api/v1/meta`:
     `{"networkingImplemented":false,"project":"Java Network Programming Assignment 02","name":"ThogaMura","scaffold":true}`.
6. **Credential safety:**
   - Root `.env` excluded from source control via `.gitignore`.
   - `git status` verified clean; zero credentials, secrets, or dump files committed.

## First checks for a member workstation

1. Confirm Java 21 **JDK** and Maven 3.9+, not only a JRE.
2. Run `mvn -f backend/pom.xml clean verify` and record full result.
3. Copy `.env.example` to `.env` and change local password; run `docker compose up -d db`.
4. Start packaged network-server with DB environment variables and verify Flyway migration.
5. Start gateway; check `GET /api/v1/meta` returns `scaffold: true`.
6. Run the Node commands listed above again from a clean clone.
7. Only after those checks start Member 1's real socket implementation.

Do not interpret a successful frontend build or gateway metadata test as evidence that networking has been implemented.

---

## Current Implementation & Verification Status — 2026-11-22

The project has advanced from the initial starter scaffold into a fully integrated, verified multi-module application implementing all five distinct Java networking and concurrency concepts across separate processes.

### 1. Five Individually Owned Networking Concepts

| Member | Technical Domain | Implementation Components | Key Verification Tests | Status |
| --- | --- | --- | --- | --- |
| **M1** | Java TCP Sockets & Framing | `TcpFrameReader`, `TcpFrameWriter`, `TcpMessageCodec`, `FrameDeadlineInputStream`, `TcpCommandServer`, `TcpCommandClient` | `TcpSocketNetworkFramingTest` (6), `SeparateProcessTcpCommandIntegrationTest` (6), `TcpCommandServerTest` (12), `TcpCommandClientIntegrationTest` (7) | **Verified** |
| **M2** | Java URI/URL HTTP Supplier Client | `DefaultSupplierStockClient`, `SupplierSimulatorServer`, `SupplierCommandHandler`, `SupplierController` | `SupplierHttpClientIntegrationTest` (10), `SupplierSimulatorServerTest` (7), `SupplierControllerTest` (6) | **Verified** |
| **M3** | Java NIO Channels & Selectors | `NioNotificationServer`, `NioNotificationSubscriber`, `NioFrameDecoder`, `SseNotificationBridge` | `NioNotificationServerIntegrationTest` (8), `NioPrivateNetworkSecurityTest` (4), `NotificationFrameDecoderTest` (7), `NioGatewayClientIntegrationTest` (6) | **Verified** |
| **M4** | PostgreSQL Transactions & Concurrency | `DefaultStockReservationService`, `DefaultStockFulfillmentService`, `JdbcStockRequestRepository`, Flyway migrations `V1` & `V2` | `AtomicStockReservationIntegrationTest` (8), `StockFulfillmentIntegrationTest` (12), `TcpAtomicReservationSocketIntegrationTest` (7), `InventoryWorkerPoolTest` (5) | **Verified** |
| **M5** | JSSE TLS & Network Security | `TlsServerSocketFactory`, `TlsSocketFactory`, `NetworkSecurityProperties`, `NetworkStatusController` | `TlsTcpCommandServerIntegrationTest` (8), `TlsTcpCommandClientIntegrationTest` (10), `NetworkStatusControllerTest` (9), `CorsAndSecurityHeadersFilterTest` (5) | **Verified** |

### 2. Frontend Next.js Client Implementation

The Next.js frontend has transitioned from placeholder notice banners to interactive client components backed by the Spring Boot API Gateway:
- **Products Catalog (`/products`):** Live stock levels, visual health indicators, and real-time SSE cache invalidation via `useStockEvents`.
- **Stock Request Form (`/requests/new`):** Canonical UUID v4 idempotency key generated once and preserved across retries, atomic reservation response handling.
- **Request Lifecycle Filter (`/requests`):** Filter by `ACCEPTED`, `REJECTED`, `PICKED`, and `DISPATCHED` states with full decision audit reasons.
- **Warehouse Queue (`/warehouse`):** Picker/dispatch action queue with transitions from `ACCEPTED -> PICKED -> DISPATCHED` and double-dispatch prevention.
- **Advisory Supplier Inquiry (`/suppliers`):** Real-time lookup against the mock supplier simulator returning `AVAILABLE`, `CONFIRMED ZERO`, `UNKNOWN`, or outage status without mutating local warehouse stock.
- **Frontend Automated Verification:** 16 unit tests across 3 suites (`tests/routeDiscovery.test.ts`, `tests/networkStatusUx.test.ts`, `tests/inventoryUx.test.ts`) passing via `node --experimental-strip-types --test`. Zero ESLint warnings, clean TypeScript compilation, and successful Next.js production build (`npm run build`).

### 3. Security, Hardening & Contract Invariants Verified

- **Canonical UUID v4 Validation (#63):** Rejects invalid characters, non-hexadecimal symbols, and incorrect length across all endpoints.
- **Slow-Trickle TCP Frame Deadline (#64):** `FrameDeadlineInputStream` enforces total elapsed byte-delivery deadline, preventing connection exhaustion attacks.
- **Bounded Responses (#60):** PostgreSQL queries for request and movement history enforce bounded record limits, ensuring serialized JSON frames never exceed 65,536 bytes.
- **Monotonic Version Sequence (#67):** Flyway `V2__change_version_sequence.sql` creates PostgreSQL sequence `warehouse_change_version_seq` guaranteeing monotonic change hints across central server restarts.
- **Strict Supplier Validation & Failure Classification (#65, #66):** Rejects payload mismatches, timestamps within 5-minute drift, and distinct classification between central warehouse outages (`HTTP 503 WAREHOUSE_UNAVAILABLE`) and supplier outages (`HTTP 502 SUPPLIER_UNAVAILABLE`).
- **Truthful TLS Diagnostics (#70):** Gateway health diagnostic verifies live TLS socket handshake with the central server, distinguishing `OPERATIONAL`, `UNREACHABLE`, `MISCONFIGURED`, and `DISABLED` without leaking credentials or internal file paths.

### 4. Remaining Delivery Tasks

The remaining open issues represent human demonstration and final coursework packaging activities:
- **Issue #2:** Formal lecturer proposal and architecture sign-off (academic procedure).
- **Issue #27:** Full two-machine physical LAN demonstration following `docs/DEMO_SCRIPT.md`.
- **Issue #28:** Compilation of the final coursework PDF report and Moodle submission bundle.
