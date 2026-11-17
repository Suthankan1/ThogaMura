# Five-week implementation roadmap — v2 planning baseline

Dates depend on team availability. None of the feature checkboxes below are claims of completed work. Freeze the networking concepts with the lecturer before assignment.

## Week 1 — contracts and two-device topology
- [ ] Approve the five distinct concepts; assign names/GitHub handles.
- [ ] Agree on private LAN topology: branch laptop (Next/gateway) -> central warehouse laptop (network server/Postgres). Configure host/IP; no UDP discovery.
- [ ] Freeze newline TCP protocol, request UUID semantics and read-only supplier HTTP contract.
- [ ] M1 demonstrates raw PING over two JVMs; M4 DB migration/transaction prototype; M5 test certificates; M2 mock supplier service skeleton; M3 selector accept-loop skeleton.
- [ ] Record current build status; starter is **not** a completed app.
**Gate:** raw socket round trip and independently running mock supplier HTTP endpoint exist.

## Week 2 — correct end-to-end reservation
- [ ] M1 socket request framing/correlation, API gateway REST adapter and timeouts.
- [ ] M4 PostgreSQL atomic reservations, persisted receipts and response replay.
- [ ] Shared Next.js products/stock request screens.
- [ ] M5 wraps the TCP command channel with trusted TLS; no plaintext fallback.
**Gate:** branch browser -> Java TLS/TCP -> central DB accepts/rejects correctly after retry.

## Week 3 — differentiated network services
- [ ] M2 uses Java `URI`/`HttpURLConnection` to query independent supplier mock when item stock is insufficient.
- [ ] M3 NIO server connects multiple branch gateway subscribers and bridges small signals into SSE invalidation/refetch.
- [ ] M4 implements PICKED/DISPATCHED and one stock movement.
- [ ] Shared warehouse queue, history and advisory supplier widget.
**Gate:** all five concepts have observable end-to-end paths.

## Week 4 — hardening and negative cases
- [ ] M1 split/multiple frames, dropped acknowledgment, timeout and same-ID retry.
- [ ] M2 supplier 404/503/timeout/bad-response handling; URL allowlisting.
- [ ] M3 partial write, slow subscriber, disconnect/reconnect and fresh state.
- [ ] M4 real concurrency, restart receipt replay and double-dispatch protection.
- [ ] M5 untrusted/incorrect TLS identity, secrets/log review.
- [ ] Private-LAN-only NIO and no insecure WAN exposure documented.
**Gate:** each member's success+failure proof passed.

## Week 5 — presentation and submission
- [ ] Full clean-clone Java frontend builds, PostgreSQL and two-machine tests.
- [ ] Capture real logs/screenshots, authored source evidence and limitations.
- [ ] Report clearly distinguishes a mock supplier from a real external API.
- [ ] Export report PDF and complete source to a single verified ZIP; one designated Moodle submission.

## Task and evidence process
Each issue gets an owner (once known), objective, small slice, tests, failure demo and review. Never merge automatically generated networking code without proving actual socket or HTTP communication.
