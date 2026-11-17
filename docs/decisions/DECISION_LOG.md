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

## ADR-004 — TLS identity policy (open)

**Options:** server-authenticated TLS only, or mutual TLS using an approved gateway client certificate.

**Recommended for lecture demo:** mutual TLS if schedule permits; verify server certificate in all cases. Document what was actually implemented and tested.

## ADR-005 — Frontend/database revision from original PDF (accepted for starter)

**Context:** The first idea proposal used Java Swing and an embedded H2 database. The team is comfortable with Next.js, Spring Boot and PostgreSQL and explicitly requested a modern frontend/backend starter.

**Decision:** Next.js frontend, Spring Boot HTTP/SSE gateway, separate Java socket server and PostgreSQL. Retain the five independent Java networking demonstrations.

**Consequence:** More application wiring and a two-process Java boundary. **The Markdown architecture and contracts supersede the old PDF's stack.** See `docs/proposal/README.md`.

## Pending group sign-off

- [ ] Five names, ownership and lecturer approval
- [ ] Exactly which stock statuses and transition errors
- [ ] Mutual TLS or server TLS only
- [ ] Local demo deployment machines and client connection options
- [ ] Authentication limitation explicitly accepted for coursework-only demo

Use a new dated ADR section when changing a frozen contract, not an undocumented silent edit.

## ADR-006 — Remove UDP discovery and define practical network features (2026-11-17, accepted as v2 design)

**Problem:** In a fixed hostname/IP deployment, UDP auto-discovery provides little business value, and a standard web browser cannot implement Java UDP discovery. A same-machine gateway->warehouse socket hop also obscured the business reason for custom TCP/NIO.

**Decision:** Model an actual **branch-side gateway -> central warehouse machine** topology. Use a configured warehouse host, not UDP discovery. Preserve M1 custom Java TCP commands and retries, M3 selector-based central change feed to multiple gateways, M4 concurrent PostgreSQL stock reservations, M5 JSSE validated TLS. Replace M2 with a **read-only supplier stock inquiry** using Java `URI`/`HttpURLConnection` to an independent supplier simulator for deterministic real network testing. No direct commercial supplier claim or automatic purchasing.

**Security and scope:** TLS commands are mandatory. The existing unencrypted NIO invalidation port remains private-demo-LAN/firewall only and cannot be deployed over WAN until encrypted/authenticated. Browser auth/roles and public HTTPS are future work. No offline queue; no second inventory database.

**Implementation state:** Design/documentation only; code and old UDP backlog need migration. Get lecturer approval for distinct M2 concept before student ownership assignment.
