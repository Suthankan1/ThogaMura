# Architecture and trust boundaries — v2

## Deployment roles (two-machine LAN demonstration)

1. **Branch machine:** Next.js browser UI (`frontend/`, port 3000) and Spring Boot API gateway (`backend/api-gateway/`, port 8080). Gateway exposes REST/SSE, holds configured warehouse host, and uses **Java TLS/TCP** to communicate with central server. It never accesses PostgreSQL directly.
2. **Warehouse machine:** Spring Boot **Java network server** (`backend/network-server/`), custom `java.net` TLS/TCP listener, NIO selector change feed, bounded worker pool, stock persistence in PostgreSQL. No duplicate REST write path.
3. **Supplier availability simulator:** separate lightweight HTTP process (`lk.thogamura.networkserver.integration.supplier.SupplierSimulatorServer`), running on configurable port (default 8090) with an allowlisted URL, queried by Member 2's central Java `HttpURLConnection` client (`DefaultSupplierStockClient`) for advisory stock availability. Timeouts, 503 errors, and invalid JSON return structured UNAVAILABLE/UNKNOWN without mutating warehouse stock.

This boundary is justified by **actual branch-to-warehouse process/machine separation**, not by claiming a raw TCP protocol is superior to HTTP.

## Architecture

```text
Branch browser --HTTP/SSE--> Branch gateway --JSSE TLS/TCP--> Central Java network server --> PostgreSQL
                               ^                                  |
                               |-- NIO version-only change feed --|
                                                                  |
                                             HTTP/HTTPS supplier availability lookup
```

## Ports (examples, configurable)

| Port | Protocol | Direction | Notes |
| --- | --- | --- | --- |
| 3000 | HTTP | Browser -> Next.js | Dev only |
| 8080 | HTTP/SSE | Browser -> branch gateway | Local demo only; HTTPS required on shared/untrusted network |
| 7443 | TLS/TCP | Branch gateway -> central server | Stock business commands; certificate/identity validation mandatory |
| 7444 | NIO TCP | Branch gateway -> central server | **Private/demo LAN only**, firewall-restricted, version-only invalidation; do not expose to internet |
| 8090 | HTTP | Central -> supplier simulator | Test/demo stub only; production supplier connection would use HTTPS |
| 5432 | PostgreSQL | Central Java server only | No browser/gateway DB access |

**No UDP port or broadcast discovery**. `THOGAMURA_NETWORK_HOST` is explicitly configured via hostname or IP. TLS validation must be based on a trusted configured identity, not only a discovered IP.

## Command workflow

1. Shop user submits one-SKU request through Next.js.
2. Gateway sends framed JSON via TLS/TCP to warehouse server with a stable idempotency UUID.
3. Server's bounded worker pool executes a PostgreSQL transaction that serializes competing stock decisions.
4. The request and durable receipt commit; server sends original ACCEPTED/REJECTED result.
5. NIO broadcaster emits only a monotonically increasing **change version** after commit; gateway re-emits browser SSE `stock-changed`.
6. Browser refetches authoritative product/request data over REST -> TLS/TCP.
7. Warehouse worker picks/dispatches; on dispatch both reserved and on-hand decrease, and a movement is inserted exactly once.
8. If warehouse stock is insufficient, user may separately request an **advisory** supplier availability check. It does not reserve or modify stock.

## Failure and security boundaries

- TCP framing and timeouts: see `contracts/NETWORK_PROTOCOL_V1.md`. An ambiguous timeout is **not** confirmation of rejection; retry with the same requestId.
- Server receipt and inventory changes must commit transactionally.
- NIO delivery is best effort; SSE/browser must refetch on reconnect and page focus.
- NIO 7444 is **not production-secure** on an untrusted network. Allow only the private demo network and do not carry any inventory, usernames, tokens or credentials on it. A WAN version needs an authenticated encrypted feed (TLS/SSLEngine or a correctly configured VPN).
- Browser/API local dev HTTP is not production secure; require HTTPS and user auth before real use.
- Supplier URL is server-configured and allowlisted, not arbitrary user input. Timeouts, non-200 and malformed results become UNKNOWN/UNAVAILABLE, never counterfeit stock values.
- Do not commit private certificates or supplier credentials.

## Invariants

- The central inventory DB is the only source of truth.
- No bypass from browser to database or direct Spring HTTP mutation on central server.
- Every member feature crosses a real process/network boundary and has one success + one failure demonstration.
- Network features and design trade-offs are detailed in `NETWORK_FEATURES_V2.md`.
