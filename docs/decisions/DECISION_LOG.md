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
