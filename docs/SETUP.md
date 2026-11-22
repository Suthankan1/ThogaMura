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

## Unified One-Command Orchestration

To start and coordinate all services (PostgreSQL, Central Network Server, Supplier Simulator, API Gateway, and Next.js Web UI) with automatic dependency ordering and health checking:

```bash
# Start all components:
./start.sh

# Check live health and process status:
./start.sh status   # or ./status.sh

# Stop all background processes and containers cleanly:
./start.sh stop     # or ./stop.sh

# Tail component logs:
./start.sh logs [server|gateway|frontend|supplier]
```

## Manual developer workflow

```bash
docker compose up -d db
docker compose ps
```

PostgreSQL runs in Docker Compose (mapped to host port `5432` by default) and persists in the named volume `thogamura_postgres_data`. If your workstation already has a local PostgreSQL service running on port 5432, specify `POSTGRES_PORT=5433` (or another free port) in your root `.env` and update `THOGAMURA_DB_URL=jdbc:postgresql://localhost:5433/thogamura`.

`docker compose down` stops containers without deleting the volume; **never run `down -v` on a database containing data worth keeping**.

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

## TLS certificate setup (Member 5)

Command traffic over port 7443 requires server-authenticated TLS (ADR-004). Generate synthetic local development certificates using standard Java `keytool`:

```bash
# Generate dev CA, warehouse server keystore, and gateway truststore:
./scripts/generate-dev-certs.sh

# For a two-laptop demo, include the warehouse laptop's LAN IP in the certificate SAN:
./scripts/generate-dev-certs.sh --lan-ip 192.168.8.100
```

All credentials are saved to `.certs/` and excluded from Git. See [TLS Certificate and Truststore Workflow](TLS_CERTIFICATES.md) for full details.

The orchestration script supports explicit TLS mode configuration:

```bash
# Loopback development only
THOGAMURA_TLS_ENABLED=false
THOGAMURA_TLS_SERVER_ENABLED=false
THOGAMURA_TLS_CLIENT_ENABLED=false

# Validated LAN/TLS demo
THOGAMURA_TLS_ENABLED=true
THOGAMURA_TLS_SERVER_ENABLED=true
THOGAMURA_TLS_CLIENT_ENABLED=true
THOGAMURA_TLS_HOSTNAME_VERIFICATION=true
THOGAMURA_WAREHOUSE_HOST=192.168.8.100
THOGAMURA_TLS_KEYSTORE_PATH=.certs/server-keystore.p12
THOGAMURA_TLS_KEYSTORE_PASSWORD=<keystore password>
THOGAMURA_TLS_TRUSTSTORE_PATH=.certs/gateway-truststore.p12
THOGAMURA_TLS_TRUSTSTORE_PASSWORD=<truststore password>
```

The startup and status commands fail closed when TLS is enabled without the required
certificate material or password. A non-loopback warehouse host cannot use plaintext
command transport. Do not print or commit the passwords.

Verify the runtime TLS configuration without starting Docker or Java processes:

```bash
./scripts/verify-tls-runtime.sh
```

## Two-machine LAN demo

Run server + PostgreSQL on the warehouse laptop; run gateway + browser on the shop laptop. Bind socket listeners to a suitable LAN interface, configure firewall rules for the small required ports, verify TLS server name/SAN and truststore, and configure `THOGAMURA_SERVER_HOST` to the central machine's trusted hostname/IP; there is no UDP discovery in v2. Restrict NIO port 7444 to the private demo LAN and do **not** expose it publicly. Run the independent supplier HTTP stub on port 8090 only if testing M2. Use fictitious demo items; never expose PostgreSQL publicly.
