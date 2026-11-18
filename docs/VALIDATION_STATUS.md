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
