# ThogaMura — agent instructions (Codex and shared baseline)

Read `README.md`, `docs/NETWORK_FEATURES_V2.md`, `docs/PROJECT_CONTEXT.md`, `docs/ARCHITECTURE.md`, `docs/TEAM_WORKLOADS.md`, and the relevant `docs/contracts/*` before changing implementation. Read `docs/ai/CONTEXT_PACK.md` for a fast task introduction.

## Hard requirements

- Assignment 02 demands actual Java `java.net` and NIO networking, with five distinct individually owned concepts. Do not convert the core to ordinary REST calls, gRPC, WebSockets or ORM-only CRUD.
- Browser cannot use Java sockets: Next.js -> Spring Boot gateway via HTTP/SSE -> **separate JVM process** over Java sockets -> PostgreSQL.
- Only `network-server` accesses PostgreSQL. Never let the gateway/frontend shortcut inventory commands around socket transport.
- Reserve stock in a single database transaction; preserve `available = on_hand - reserved >= 0`; persist request IDs and process retries idempotently.
- Do not send stock/customer information over the unencrypted NIO feed. The NIO feed must stay on a private/firewall-restricted demo LAN and cannot be exposed over WAN. Business commands go over validated TLS.
- M2 is **HTTP/URL supplier stock inquiry**, not UDP auto-discovery. Use an independent mock supplier service for demos and a configured allowlisted URL; do not invent supplier results or implement automatic purchasing.
- Treat every request as untrusted: bounded frames, validation, timeouts, no swallowing exceptions, no logging of credentials.
- The deployed warehouse hostname/IP is configured: do not reintroduce UDP discovery without a new justified ADR.
- Do not invent product requirements or change protocol v1 without documenting the decision and asking the team.

## Delivery workflow

1. Identify member and scope in `docs/TEAM_WORKLOADS.md` and select one checklist item from `docs/ROADMAP.md`.
2. Explain the intended behavior and boundary. Implement the smallest viable slice, ideally student-written.
3. Add a passing happy-path test and a failure/edge-case test, plus a manual network demonstration.
4. Run targeted tests and relevant build checks; report what was **not** actually executed.
5. Keep PRs narrow and record ADRs for interface, security and data-model changes.
6. Update the named member handoff file under `docs/handoffs/` when handing work to another person or AI.

## Code layout

- Java: `backend/protocol`, `backend/network-server`, `backend/api-gateway`.
- Next.js: `frontend/src/app`, `frontend/src/components`, `frontend/src/lib`.
- PostgreSQL migrations: `backend/network-server/src/main/resources/db/migration`.
- Tests belong to the module that owns the code.

## Forbidden shortcuts

- No pretend success responses or mocked server state in production paths.
- No AI-generated implementation presented as independent student work.
- No generated code commits without comprehension, individual ownership, and review.
- No secrets, `.env`, actual keystores/certificates, or personal data in source.
- Never claim socket/TLS/NIO integration works based only on a context-start test.

For agent-specific entrypoints, also see `CLAUDE.md`, `GEMINI.md`, `.cursor/rules` and `.github/copilot-instructions.md`. Keep shared truth here and in `docs/`.
