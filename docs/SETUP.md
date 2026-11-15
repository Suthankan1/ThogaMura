# Local setup and developer workflow

## Prerequisites

- JDK **21** (`java -version`).
- Maven **3.9+** (`mvn -version`). Maven Wrapper is not bundled; do not assume `./mvnw` exists.
- Node.js **22+** and npm (`node -v`, `npm -v`).
- Docker + Docker Compose.
- IDE of choice: IntelliJ, VS Code, Cursor, Antigravity; tools do not change the contracts.

## Initial environment

1. Copy `.env.example` to `.env` at the root and choose a **new local-only** password.
2. Docker Compose automatically reads root `.env` and configures PostgreSQL.
3. For each Java process, export `THOGAMURA_DB_URL`, `THOGAMURA_DB_USER` and `THOGAMURA_DB_PASSWORD` **only for the network-server**; gateway must not receive DB credentials.
4. For Next.js, copy `frontend/.env.example` to `frontend/.env.local`.

## Run database

```bash
docker compose up -d db
docker compose ps
```

PostgreSQL runs locally at port 5432 and persists in a Docker named volume. `docker compose down` stops containers without deleting the volume; **never run `down -v` on a database containing data worth keeping**.

## Java builds

```bash
mvn -f backend/pom.xml clean verify
```

Once the Java apps are actually implemented, package the reactor and start the two **separate JVMs** in different terminals:

```bash
mvn -f backend/pom.xml package
java -jar backend/network-server/target/network-server-0.1.0-SNAPSHOT.jar
# In a second terminal:
java -jar backend/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar
```

The repository currently contains only startup skeletons, not working socket listeners. The network-server may exit after initialization until member-owned background listeners are implemented.

## Frontend

```bash
npm --prefix frontend ci
npm --prefix frontend run dev
```

Open http://localhost:3000. The current page is an honest scaffold, not populated from the backend. Start the gateway independently when it is ready.

## Useful checks

```bash
mvn -f backend/pom.xml test
npm --prefix frontend run typecheck
npm --prefix frontend run build
git diff --check
```

## Two-machine LAN demo

Run server + PostgreSQL on the warehouse laptop; run gateway + browser on the shop laptop. Bind socket listeners to a suitable LAN interface, configure firewall rules for the small required ports, verify TLS server name/SAN and truststore, and use manual server IP when UDP broadcast is blocked. Use fictitious demo items; never expose PostgreSQL publicly.
