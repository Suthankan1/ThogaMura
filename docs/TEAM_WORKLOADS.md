# Five distinct networking workloads — v2

Member numbers are placeholders, **not real assignments**. Confirm lecturer approval and replace with actual names tomorrow. Every member owns source, automated tests and an individual live demo of one lecture concept. UI/CI/report are shared, not a substitute for assessed networking.

## M1 — TCP stock command delivery

- **Concept:** Java `Socket`, `ServerSocket`, streams, TCP connection lifecycle, frame boundaries, timeouts.
- **Business reason:** a branch gateway must send stock commands to a warehouse server on a separate machine; uncertain results must be safely retried.
- **Build:** bounded newline-JSON framing, correlation, connect/read deadlines, socket cleanup, repeatable request UUID; integration with M4's durable receipts and M5's TLS socket factory.
- **Owned:** `networkserver.transport.tcp`, `gateway.network.tcp`.
- **Prove:** real two-JVM call, split and merged frames, timeout after warehouse commit followed by same-ID retry without duplicate reservation.
- **Do not claim:** TCP by itself guarantees exactly-once application delivery.

## M2 — HTTP/URL supplier availability inquiry (replaces UDP)

- **Concept:** Java `URI`, `URL`, `HttpURLConnection`/`HttpsURLConnection`, DNS host resolution, HTTP response codes/timeouts.
- **Business reason:** warehouse rejects a request due to insufficient stock; staff can see an advisory availability/ETA from a separate supplier system.
- **Build:** central-server outbound read-only HTTP connector to **configured** supplier URL, supplier contract (`contracts/SUPPLIER_HTTP_V1.md`), response validation and error mapping. Write a tiny independent mock supplier HTTP server for reliable grading, not a fake hard-coded return inside the same method.
- **Owned:** `networkserver.integration.supplier`, supplier mock fixture, HTTP gateway adapter for `GET /suppliers/availability?sku=...`.
- **Prove:** outgoing network HTTP request to separate process + successful answer; 404, 503, timeout and invalid JSON produce explicit unknown/unavailable.
- **Security:** no arbitrary URL input/SSRF, no credentials in Git, use HTTPS in real integrations; supplier answer never changes central inventory.
- **Non-goals:** purchasing, payment, supplier account integration. Real supplier data is not available by default.

## M3 — NIO multi-gateway change signals

- **Concept:** `ServerSocketChannel`, `SocketChannel`, `Selector`, `SelectionKey`, `ByteBuffer`, non-blocking partial I/O.
- **Business reason:** several branch gateway instances need immediate warehouse change hints to refresh their operator browsers.
- **Build:** one selector-based server, 10+ simulated gateway subscribers, bounded per-client buffers, `OP_WRITE` only for pending bytes, gateway subscriber/reconnect, SSE invalidation bridging.
- **Owned:** `networkserver.notifications`, `gateway.network.notifications`.
- **Prove:** genuine 10-client NIO network test, slow reader/partial write, disconnect and missed event followed by authoritative refetch.
- **Security:** strictly private-LAN, version-only feed. No public exposure. Encrypted/authenticated feed required before WAN deployment.
- **Do not claim:** NIO means events are durable or that 10 clients require selectors for performance.

## M4 — Concurrent reservations and durable idempotency

- **Concept:** `ExecutorService`, bounded worker pool/queues, threads, race conditions, shared-state synchronization.
- **Business reason:** simultaneous branch stock requests cannot both reserve unavailable items.
- **Build:** worker executor + transactional conditional stock update or `SELECT FOR UPDATE`; persisted requestId receipt and original result; PICKED/DISPATCHED state machine and one movement.
- **Owned:** `networkserver.concurrent`, `networkserver.inventory`, `networkserver.persistence`.
- **Prove:** from 10 available, parallel requests for 8 each yield at most one accept; restart, duplicate command, double dispatch and row constraint tests.
- **Important:** Java synchronization is not a replacement for PostgreSQL transaction integrity.

## M5 — TLS trust and identity for commands

- **Concept:** `SSLSocket`, `SSLServerSocket`, JSSE handshake, certificates/truststores and hostname verification.
- **Business reason:** branch must verify central server identity and protect command data while it crosses the LAN.
- **Build:** secure socket factories and configuration for M1's framed protocol; validated trust chain and server identity; optional mutual TLS if time allows; test certificate setup outside repository.
- **Owned:** `networkserver.security`, `gateway.network.security`.
- **Prove:** trusted connection completes an actual command; untrusted/wrong-name server fails closed; no plaintext fallback.
- **Important:** TLS does not substitute for human authentication, roles or permission checks.

## Shared integration and fair ownership

- Web UI: product list, request create, warehouse queue/history, supplier availability/unknown state, network health.
- Spring Boot gateway: REST/SSE and TCP/TLS bridges, no database write shortcuts.
- Documentation, reports, CI and two-machine LAN verification: shared.
- Branch names: `feat/m1-tcp-commands`, `feat/m2-supplier-http`, `feat/m3-nio-feed`, `feat/m4-concurrency`, `feat/m5-tls`.
- Each member attaches source/PR, real test command/output, observed success and negative scenario before declaring done.

## Dependency order

1. Freeze v2 command and HTTP API contracts + supplier mock schema, choose local LAN deployment topology.
2. M1 command PING over two JVMs; M4 DB transaction tests; M5 certificate fixtures in parallel.
3. Integrate M1 + M4 + TLS into one browser-to-warehouse path.
4. M2 supplier adapter and M3 NIO feed are independently testable against the established central server.
5. Complete shared UI, integration tests, individual five demos and report.

See `NETWORK_FEATURES_V2.md` for the explicit why, what and constraints.
