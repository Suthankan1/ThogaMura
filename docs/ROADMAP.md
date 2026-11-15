# Iterative build roadmap — suggested five weeks

This is a planning baseline, not a commitment to dates. Move from one verified vertical slice to the next. Do not generate the whole system in one AI prompt.

## Week 1 — contract and thin transport

- [ ] Assign students and lecturer-approve distinct concepts.
- [ ] Freeze port plan, command types, requestId and framing.
- [ ] Bootstrapped Maven modules, Next starter and PostgreSQL compose reviewed.
- [ ] M1: first real command response between gateway JVM and network-server JVM.
- [ ] M4: database schema, data fixtures and reservation design test.
- [ ] M5: test-only certificate generation / trust plan.
- [ ] Shared: decide whether gateway client certificates are required.

**Gate:** a real Java socket packet crosses process boundaries; no stock logic needed yet.

## Week 2 — first durable stock reservation

- [ ] Product query, request submission and persisted accepted/rejected receipt.
- [ ] Atomic reservation against concurrent calls; persisted idempotency.
- [ ] Basic HTTP translation to/from TCP.
- [ ] First Next.js product view + new request form.
- [ ] Tests: oversubscription and duplicate requestId.

**Gate:** submit from browser, server commits through network socket, refresh shows DB truth.

## Week 3 — discovery, notifications and warehouse

- [ ] M2: UDP discovery and manual host fallback.
- [ ] M3: selector-based multi-subscriber notifications, gateway bridge to SSE.
- [ ] Warehouse PICKED and DISPATCHED commands; movement history.
- [ ] Next.js warehouse queue, request history and connection states.
- [ ] Tests: 10 clients, reconnect, missed event/refetch, double dispatch.

**Gate:** two-machine LAN workflow and one eventual-update demonstration.

## Week 4 — security and hardening

- [ ] M5: TLS validated command channel; negative certificate handshake.
- [ ] M1: bounded framing and timeout/retry failure behavior.
- [ ] M4: concurrent race + restart replay + atomic dispatch hardening.
- [ ] Validate access boundaries, logging, secrets and graceful cleanup.
- [ ] Stable UI states, screen reader/keyboard and mobile checks.

**Gate:** all five independent feature demos and failure cases pass.

## Week 5 — verification and submission

- [ ] Full regression, build from clean clone, LAN rehearsal.
- [ ] Screenshots and real test outputs for report, plus limitations.
- [ ] Five students present their own code and evidence.
- [ ] Export final PDF report under `reports/final/` (not yet included).
- [ ] Create single course ZIP: complete source + PDF report, excluding secrets and build artifacts.
- [ ] One designated member submits to Moodle.

## Backlog convention

Every task must have **owner**, **small observable behavior**, **test**, **manual demo**, and **merge acceptance**. Keep tracked progress in `docs/handoffs/` or GitHub issues. Avoid dates and completed checkboxes that have not been verified.
