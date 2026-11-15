# Architecture and trust boundaries

## Three application processes plus the database

1. **Next.js UI** (`frontend/`): browser pages and states; consumes HTTP and SSE. It does not open raw TCP/UDP sockets or connect to PostgreSQL.
2. **Spring Boot API gateway** (`backend/api-gateway/`): routes browser requests to a **real socket client**, translates results to HTTP, subscribes to NIO notifications and emits SSE invalidation events. Does not know JDBC.
3. **Spring Boot network server** (`backend/network-server/`): hosts custom Java TLS/TCP command listener, UDP discovery responder, NIO change notifier, bounded worker pool and PostgreSQL transaction layer. It need not expose HTTP to the browser.
4. **PostgreSQL**: accessed only by the network server; durable stock and request source of truth.

## Ports (defaults, configurable)

| Port | Protocol | Who connects | Purpose |
| --- | --- | --- | --- |
| 3000 | HTTP | Browser | Next.js local development |
| 8080 | HTTP / SSE | Browser | Gateway REST and change stream |
| 7443 | TLS over TCP | Gateway | Inventory command channel |
| 7444 | Unencrypted Java NIO TCP | Gateway | Minimal public-safe change version only |
| 7445 | UDP | Gateway | LAN discovery; manual IP fallback |
| 5432 | PostgreSQL | Network server only | Persistent data |

The 7444 feed is **not confidential or authoritative**: it contains only a monotonically increasing version (not IDs, credentials or stock details). On each signal the gateway/browser fetches data via authenticated paths.

## Normal flow

1. Shop operator submits a one-SKU request from Next.js.
2. Browser sends `POST /api/v1/requests` to gateway with requestId and quantity.
3. Gateway validates web envelope and sends newline-delimited JSON using a Java TLS socket client. Member 1 owns framing/command semantics; Member 5 owns TLS validation.
4. Network server dispatches through bounded workers, enters database transaction, validates and atomically reserves inventory, writes durable request receipt.
5. Server returns `ACCEPTED` or `REJECTED` on command socket. Gateway maps to HTTP response.
6. Server publishes a version-only NIO change signal. Gateway emits SSE invalidation. Browser refetches via REST.
7. Picker transitions an accepted request to `PICKED` and `DISPATCHED`. On dispatch, on-hand and reserved counts change together and movement is appended.

## Failure behavior

- TCP can split or merge frames. Read until newline with UTF-8; bound each message (proposed 64 KiB).
- A timeout after successful server commit is **ambiguous**, not failure: gateway retries using the **same requestId**.
- Persist the requestId receipt and enforce uniqueness in PostgreSQL so server restart cannot reapply it.
- Browser may miss NIO/SSE events. After reconnect and on page focus, fetch fresh state.
- UDP discovery can fail on campus networks; expose manual server address setting.
- Reject untrusted TLS certificates, protocol mismatches, unauthenticated write commands and malformed payloads.
- Keep worker pools bounded and close/clean up sockets and channels.

## Operational safety

No demo secrets in Git. TLS keystore/truststore created per developer outside repository and referenced with env variables. Never disable certificate or hostname verification to make the demo pass. Local demo-only HTTP between browser and gateway is acceptable on localhost; never expose sensitive traffic over public unencrypted networks. Consider an HTTPS reverse proxy before any real use.

## Architectural invariant

**Web UI never bypasses the Java socket server to mutate stock.** The assessment call path remains visible, instrumentable and testable.
