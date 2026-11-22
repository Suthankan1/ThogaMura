# Five-week implementation roadmap — v2 execution status

> **Status summary (23 November 2026).** The software, authenticated workspace and private-bridge rehearsal have verified evidence. The coursework bundle is assembled for group review. Physical LAN rehearsal and the designated student's Moodle upload remain open.

## Week 1 — contracts and two-device topology
- [x] Approve the five distinct concepts; assign names/GitHub handles.
- [x] Agree on private LAN topology: branch laptop (Next/gateway) -> central warehouse laptop (network server/Postgres). Configure host/IP; no UDP discovery.
- [x] Freeze newline TCP protocol, request UUID semantics and read-only supplier HTTP contract.
- [x] M1 demonstrates raw PING over two JVMs; M4 DB migration/transaction prototype; M5 test certificates; M2 mock supplier service skeleton; M3 selector accept-loop skeleton.
- [x] Record current build status; starter is **not** a completed app.
**Gate:** raw socket round trip and independently running mock supplier HTTP endpoint exist. *(PASSED)*

## Week 2 — correct end-to-end reservation
- [x] M1 socket request framing/correlation, API gateway REST adapter and timeouts.
- [x] M4 PostgreSQL atomic reservations, persisted receipts and response replay.
- [x] Shared Next.js products/stock request screens.
- [x] M5 wraps the TCP command channel with trusted TLS; no plaintext fallback.
**Gate:** branch browser -> Java TLS/TCP -> central DB accepts/rejects correctly after retry. *(PASSED)*

## Week 3 — differentiated network services
- [x] M2 uses Java `URI`/`HttpURLConnection` to query independent supplier mock when item stock is insufficient.
- [x] M3 NIO server connects multiple branch gateway subscribers and bridges small signals into SSE invalidation/refetch.
- [x] M4 implements PICKED/DISPATCHED and one stock movement.
- [x] Shared warehouse queue, history and advisory supplier widget.
**Gate:** all five concepts have observable end-to-end paths. *(PASSED)*

## Week 4 — hardening and negative cases
- [x] M1 split/multiple frames, dropped acknowledgment, timeout and same-ID retry.
- [x] M2 supplier 404/503/timeout/bad-response handling; URL allowlisting.
- [x] M3 partial write, slow subscriber, disconnect/reconnect and fresh state.
- [x] M4 real concurrency, restart receipt replay and double-dispatch protection.
- [x] M5 untrusted/incorrect TLS identity, secrets/log review.
- [x] Private-LAN-only NIO and no insecure WAN exposure documented.
**Gate:** each member's success+failure proof passed. *(PASSED)*

## Week 5 — presentation and submission
- [x] Run the Java and frontend checks and exercise the private segmented test topology.
- [x] Record measured logs, a browser screenshot, and software limitations.
- [x] Distinguish the independent supplier simulator in the report.
- [x] Assemble and verify the source ZIP and group-review report.
- [ ] Run the two-device LAN with course firewall rules. Each member supplies their own source attribution and live proof (#27).
- [ ] Confirm student IDs, course approval and the submission slot. A designated student uploads the reviewed ZIP once (#28).

## Task and evidence process
Each issue gets an owner (once known), objective, small slice, tests, failure demo and review. Never merge automatically generated networking code without proving actual socket or HTTP communication.
