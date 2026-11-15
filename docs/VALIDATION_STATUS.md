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

## First checks for a member workstation

1. Confirm Java 21 **JDK** and Maven 3.9+, not only a JRE.
2. Run `mvn -f backend/pom.xml clean verify` and record full result.
3. Copy `.env.example` to `.env` and change local password; run `docker compose up -d db`.
4. Start packaged network-server with DB environment variables and verify Flyway migration.
5. Start gateway; check `GET /api/v1/meta` returns `scaffold: true`.
6. Run the Node commands listed above again from a clean clone.
7. Only after those checks start Member 1's real socket implementation.

Do not interpret a successful frontend build or gateway metadata test as evidence that networking has been implemented.
