# Decision log

## ADR-001 — Explicit Java socket boundary (accepted for starter)

**Date:** 2026-11-15

**Context:** Assignment needs directly implemented `java.net` and NIO, while team is comfortable with Next.js/Spring Boot.

**Decision:** Separate network-server JVM from Spring Boot HTTP gateway. All stock commands cross Java sockets; Next uses HTTP/SSE.

**Consequences:** More local processes but clearer lecture evidence and no REST shortcut.

## ADR-002 — PostgreSQL and single SKU per request (proposed)

**Decision:** PostgreSQL is the only durable state store. A request contains one SKU and one positive quantity; balance checks happen transactionally.

**Open:** Are reports/history enough for the shop? Confirm with lecturer and one user.

## ADR-003 — Best-effort NIO invalidation (proposed)

**Decision:** Only `CHANGED/version` over NIO; browser refetches via authoritative TLS-backed REST.

**Reason:** Avoid exposing stock data on an unencrypted feed and avoid treating a transient socket event as durable history.

## ADR-004 — TLS identity policy (accepted)

**Date:** 2026-11-18

**Context:** Command traffic between branch gateways and the central warehouse server crosses a shared demonstration network on port 7443. We evaluated two models:
1. Server-authenticated TLS: gateway validates warehouse server certificate and hostname against local demo CA truststore.
2. Mutual TLS (mTLS): both server and client authenticate using certificates.

**Decision:** Adopt **Server-Authenticated TLS** as the mandatory coursework MVP standard for all command traffic on port 7443.
- The Gateway (`SSLSocket`) strictly validates the warehouse server's X.509 certificate against the trusted coursework root CA and verifies the target hostname/IP against the certificate Subject Alternative Name (SAN).
- The Warehouse Server (`SSLServerSocket`) presents a valid X.509 certificate signed by the demonstration CA.
- Plaintext socket fallback is strictly forbidden; untrusted or hostname-mismatched certificates immediately fail closed.
- Mutual TLS (mTLS) is documented as an optional enhancement for advanced multi-machine demonstrations if time permits, but is not required to pass the MVP baseline.

**Consequences:** Full cryptographic protection (confidentiality, integrity, replay defense) against network eavesdropping and server spoofing without introducing fragile client certificate distribution overhead during student development.

## ADR-005 — Frontend/database revision from original PDF (accepted for starter)

**Context:** The first idea proposal used Java Swing and an embedded H2 database. The team is comfortable with Next.js, Spring Boot and PostgreSQL and explicitly requested a modern frontend/backend starter.

**Decision:** Next.js frontend, Spring Boot HTTP/SSE gateway, separate Java socket server and PostgreSQL. Retain the five independent Java networking demonstrations.

**Consequence:** More application wiring and a two-process Java boundary. **The Markdown architecture and contracts supersede the old PDF's stack.** See `docs/proposal/README.md`.

## Group sign-off record (Issue #4)

- [x] Five member ownerships confirmed: M1 (@sankharavindu), M2 (@sandunikarunarathna), M3 (@Suthankan1), M4 (@Yasiru-ruwantha), M5 (@vibhathkalsara99).
- [x] Stock request statuses frozen: `ACCEPTED`, `REJECTED`, `PICKED`, `DISPATCHED`.
- [x] Error codes enumerated and frozen across all modules.
- [x] Server-authenticated TLS accepted as MVP baseline (ADR-004).
- [x] Fixed IP/DNS addressing without UDP auto-discovery accepted (ADR-006).
- [x] Coursework security boundaries, private LAN restrictions, and synthetic data scope accepted.

Use a new dated ADR section when changing a frozen contract, not an undocumented silent edit.

## ADR-006 — Remove UDP discovery and define practical network features (2026-11-17, accepted as v2 design)

**Problem:** In a fixed hostname/IP deployment, UDP auto-discovery provides little business value, and a standard web browser cannot implement Java UDP discovery. A same-machine gateway->warehouse socket hop also obscured the business reason for custom TCP/NIO.

**Decision:** Model an actual **branch-side gateway -> central warehouse machine** topology. Use a configured warehouse host, not UDP discovery. Preserve M1 custom Java TCP commands and retries, M3 selector-based central change feed to multiple gateways, M4 concurrent PostgreSQL stock reservations, M5 JSSE validated TLS. Replace M2 with a **read-only supplier stock inquiry** using Java `URI`/`HttpURLConnection` to an independent supplier simulator for deterministic real network testing. No direct commercial supplier claim or automatic purchasing.

**Security and scope:** TLS commands are mandatory. The existing unencrypted NIO invalidation port remains private-demo-LAN/firewall only and cannot be deployed over WAN until encrypted/authenticated. Browser auth/roles and public HTTPS are future work. No offline queue; no second inventory database.

**Implementation state:** Design/documentation only; code and old UDP backlog need migration. Get lecturer approval for distinct M2 concept before student ownership assignment.

## ADR-007 — Shared Network Protocol v1, HTTP API v1, and Security Contracts Freeze (2026-11-18, accepted)

**Date:** 2026-11-18

**Context:** Issue #4 requires freezing all shared contracts before parallel implementation by the five member tracks.

**Decision:** Formally freeze the following specifications at version 1.0.0:
1. `docs/contracts/NETWORK_PROTOCOL_V1.md`:
   - Bounded newline-delimited UTF-8 JSON framing ($\le 65,536$ bytes).
   - Canonical UUID v4 `requestId` correlation.
   - Diagnostic `PING` / `PONG` command for safe connectivity and latency testing.
   - Idempotent command retry rules backed by SHA-256 fingerprints in PostgreSQL `command_receipts`.
   - Complete enumeration of frozen status values and 10 standard error codes.
2. `docs/contracts/HTTP_API_V1.md`:
   - Spring Boot Gateway REST endpoints mapping 1:1 to custom TCP wire commands.
   - `Idempotency-Key` header mapped directly to TCP `requestId`.
   - Single Server-Sent Events stream (`GET /api/v1/events`) emitting version-only cache invalidations.
   - Diagnostic `/api/v1/network-status` endpoint for 3-hop topology health verification.
3. `docs/contracts/SUPPLIER_HTTP_V1.md`:
   - Read-only external supplier stock inquiry using Java `URI`/`HttpURLConnection` to configured base URL (`THOGAMURA_SUPPLIER_BASE_URL`).
   - Standard mock schemas for available units, confirmed zero, unknown SKU (404), and supplier outage (503).
   - Strict SSRF protection and 16 KB response payload bounding.

**Consequences:** All five member tracks have unambiguous, frozen API contracts. Member 1 can implement TCP framing (Issue #6) and command listener (Issue #7); Member 4 can implement reservation transactions (Issue #10); Member 5 can construct TLS socket factories (Issue #23) with confidence that wire envelopes and status codes will not drift.

## Workspace decisions accepted by the group

All five members agreed to proceed on 23 November 2026, as confirmed in the implementation handoff.

### ADR-008 Bounded warehouse reads and request lookup

**Accepted.** M1 and M4 use bounded keyset pages in the opt-in authenticated v2 workspace. Products sort by SKU, requests by creation time and UUID, and movements by ledger ID. Request detail resolves an exact UUID from the warehouse database under the current branch scope. The v1 demonstration contract remains available in the demonstration profile.

### ADR-009 Reservation cancellation and receiving

**Accepted.** M4 permits cancellation before picking. The transaction releases the reservation, sets the terminal `CANCELLED` state, and persists the reply under the command UUID. Supervisor receiving adds on-hand units to an existing SKU, preserves reserved stock, and records the actor and inbound note in a positive immutable movement. The migration preserves legacy requests and the one-dispatch constraint.

### ADR-010 Branch identity and access

**Accepted.** M5 and the shared integration use locally provisioned operator, picker and supervisor accounts. The gateway issues an HttpOnly session and signs short-lived, command-bound identity assertions over validated TLS. The warehouse enforces role and branch rules. An enabled workspace rejects v1 business commands. An unassigned legacy request stays in the v1 demonstration dataset. NIO signals retain the version-only shape.

[Workspace wire and HTTP contract](../contracts/WORKSPACE_V2.md)


### ADR-011 Warehouse workspace UI filters

**Implemented for team review on 23 November 2026.** Add optional filters to the opted-in workspace v2 reads, bind cursor scopes to the complete query and branch scope, and include the product display name in request results. Support exact product selection through the existing v2 products command. Apply date ranges as inclusive start and exclusive end instants. Existing callers can omit every new filter. Previously issued workspace cursors expire with this query change. Warehouse authorization and database ownership stay with the network server.

The demo v1 request query now orders its existing bounded result newest-first. Authoritative pagination and exact lookup for legacy records need a separate contract decision and explicit group approval under the [v1 change policy](../contracts/NETWORK_PROTOCOL_V1.md#9-protocol-governance--modification-process). The demo UI states the limit of its search.

[Workspace contract](../contracts/WORKSPACE_V2.md)

### ADR-012 Shared shortage tasks and warehouse request history

Implemented for team review. Warehouse staff coordinate one open supplier follow-up per SKU. PostgreSQL enforces that limit with a partial unique index. Tasks use the existing signed, idempotent workspace mutation path. Owners come from the warehouse roster. Branch staff can see who is handling a shortage. Due dates are calendar dates.

The warehouse records request stages with an insert/status-change trigger so reservation, cancellation, picking and dispatch commit their history atomically. A migration backfills only recorded timestamps. Private version hints invalidate browser reads. A confirmed balance can invalidate a new draft submission while an uncertain submitted command retains its original retry key.
