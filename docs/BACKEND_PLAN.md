# Backend and persistence plan — v2

## Three Maven modules

- `backend/protocol`: shared JSON envelope/requestId conventions; minimal shared DTOs.
- `backend/api-gateway`: Spring MVC REST/SSE and raw Java TLS/TCP client; no JDBC, no database access. Branch-side runtime, `THOGAMURA_SERVER_HOST` configured (DNS/IP), no UDP client.
- `backend/network-server`: Spring Boot lifecycle for Java raw socket server, NIO version feed, bounded executor, Spring JDBC, Flyway, PostgreSQL. Central warehouse runtime. **New M2 outbound HTTP supplier connector is here**, not in the browser.

## Owner and packages

| Owner | Package area (proposed `lk.thogamura` prefix) | Main responsibility |
| --- | --- | --- |
| M1 | `networkserver.transport.tcp`, `gateway.network.tcp` | Framed TLS/TCP command semantics + retry |
| M2 | `networkserver.integration.supplier` + `tools/supplier-mock` (planned) | `URI`, `URL`, `HttpURLConnection` availability lookup |
| M3 | `networkserver.notifications`, `gateway.network.notifications` | NIO selector invalidation, SSE bridge |
| M4 | `networkserver.inventory`, `...persistence`, `...concurrent` | Worker pool, atomic stock and receipts |
| M5 | `networkserver.security`, `gateway.network.security` | JSSE socket transport and trust verification |

Actual package paths should be agreed in first PR; no feature implementation exists in the starter yet.

## Central stock database

Tables proposed in initial Flyway migration:

1. `product_stock`: SKU, name, `on_hand`, `reserved`, checks `0 <= reserved <= on_hand`.
2. `stock_requests`: request UUID, SKU, quantity, state, timestamps.
3. `stock_movements`: append-only dispatch movement, one per request.
4. `command_receipts`: unique mutating command UUID, canonical payload fingerprint, original response (including rejections).

**Reservation:** check/replay durable receipt; transactionally lock or conditionally update `product_stock`; increment `reserved` if sufficient; persist request and receipt in one commit; publish NIO hint **after commit**.

**Dispatch:** only PICKED -> DISPATCHED; decrement both `on_hand` and `reserved` once, record one movement and receipt atomically. Thread synchronization alone is not a DB guarantee.

## Network integrations

- **M1 commands:** framed UTF-8 JSON over TCP, later wrapped with M5 JSSE. Connection errors leave results uncertain; no duplicate execution after retry.
- **M2 supplier:** `GET_SUPPLIER_STOCK` is a read-only command; central Java code calls configured supplier HTTP endpoint with `URI.toURL().openConnection()` / `HttpURLConnection`; strict connect/read timeouts, HTTP status mapping, response-size bounds, parsed schema; no arbitrary user URLs and no automatic stock changes.
- **M3 NIO:** nonblocking selector fanout of minimal `CHANGED/version` signals to branch gateways; private LAN only, reconnect/refetch through command channel.
- **M5:** certificate-validated JSSE at command port; no insecure fallback.

## Startup, readiness and boundaries

Central network server does not expose separate browser-facing stock REST routes. Gateway `GET /actuator/health` proves only gateway process alive; include distinct dependency/network status when implemented. One central DB only. Supplier mock is test/demo service, not a production dependency; inventory workflow remains functional when supplier unavailable.

See `NETWORK_FEATURES_V2.md` and `ARCHITECTURE.md`.
