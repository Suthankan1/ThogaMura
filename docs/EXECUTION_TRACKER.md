# ThogaMura Autonomous Execution Tracker

**Start Date:** 2026-11-21  
**Lead Coordinator / Engineer:** Autonomous Lead Software Engineer & GitHub Repository Maintainer  
**Target:** 100% verification and delivery of remaining actionable software backlog.

---

## Issue Execution Matrix

| Issue | Title | Priority | Status | Branch / PR | Verification / Evidence |
|---|---|---|---|---|---|
| **#61** | [M5][P1] Fail closed for remote plaintext commands and disabled TLS hostname checks | P1 | CLOSED | PR #71 (Merged `a390354`) | Verified by `TlsClientConfigurationTest` (6/6), `TlsTruststoreHostnameVerificationTest` (5/5), `TlsTcpCommandClientIntegrationTest` (9/9) |
| **#62** | [M5][P1] Restrict gateway CORS and redact internal network errors | P1 | CLOSED | PR #72 (Merged `af253c3`) | Verified by `GatewayCorsSecurityTest` (5/5), `GatewayExceptionHandlerTest` (6/6), `EventsControllerTest` (4/4), `SupplierControllerTest` (8/8) |
| **#63** | [M1][P1] Enforce canonical UUID v4 request IDs at TCP boundary | P1 | CLOSED | PR #73 (Merged `94edf43`) | Verified by `TcpFrameCodecTest` (15/15), `TcpCommandServerTest` (13/13) with real socket validation |
| **#65** | [M2][P1] Reject mismatched or incomplete supplier HTTP 200 responses | P1 | CLOSED | PR #74 (Merged `0982a3a`) | Verified by `DefaultSupplierHttpClientTest` (15/15), `SupplierHttpSimulatorTest` (19/19) |
| **#59** | [M4][P2] Notify branch gateways when a stock request is rejected | P2 | CLOSED | PR #75 (Merged `2747f0c`) | Verified by `PostCommitNioNotificationIntegrationTest` (6/6), `AtomicStockReservationIntegrationTest` (14/14) |
| **#60** | [M4][P2] Bound request and movement reads to 64 KB TCP limit | P2 | IN PROGRESS | `fix/m4-bound-reads-64kb-60` | Verified by `StockFulfillmentIntegrationTest` (12/12) with oversized history test, `StockFulfillmentCommandHandlerTest` (6/6) |
| **#64** | [M1][P2] Enforce total TCP frame deadline against slow-trickle clients | P2 | PENDING | | |
| **#66** | [M2][P2] Distinguish warehouse transport outages from supplier outages | P2 | PENDING | | |
| **#67** | [M4][P2] Make warehouse change versions monotonic across restarts | P2 | PENDING | | |
| **#70** | [M5][P2] Replace hard-coded NOT_IMPLEMENTED TLS status with truthful diagnostics | P2 | PENDING | | |
| **#69** | [M2][P2] Run all frontend tests in CI rather than one named file | P2 | PENDING | | |
| **#13** | [SHARED][W2] Implement product list and stock request frontend-to-TCP path | P3 | PENDING | | |
| **#22** | [SHARED][W3] Build warehouse queue, request history and activity views | P3 | PENDING | | |
| **#30** | [M2][W4] Demonstrate supplier network failure handling in Next.js and tests | P3 | PENDING | | |
| **#26** | [SHARED][W4] Accessibility, responsive layout and network failure states | P3 | PENDING | | |
| **#14** | [M1][W2] Test TCP partial frames, disconnects and recovery on two JVMs | P3 | PENDING | | |
| **#25** | [M4][W4] Prove restart replay, receipt conflicts and dispatch concurrency | P3 | PENDING | | |
| **#68** | [SHARED][P2] Refresh stale implementation status and repair machine-local links | P4 | PENDING | | |
| **#1** | [START HERE] Five-member ownership and issue roadmap | META | ACTIVE | | |
| **#2** | [SHARED][W1] Confirm lecturer scope and five distinct member concepts | EXTERNAL | BLOCKED | | Pending human lecturer confirmation session |
| **#27** | [SHARED][W5] Full two-machine LAN acceptance rehearsal and evidence | DEMO | BLOCKED | | Requires two physical machines on LAN |
| **#28** | [SHARED][W5] Produce final PDF report and single Moodle submission ZIP | SUBMISSION| PENDING | | Coursework packaging |

---

## Log of Actions

1. Initial audit completed: Verified all 22 open issues, confirmed previous 26 merged PRs, diagnosed Docker daemon requirement for Testcontainers and npm ci for frontend build.
2. Verified baseline tests: Backend 242/242 tests passing, frontend 7/7 tests passing, tsc/lint/build passing.
3. Implemented Issue #61: Fail-closed configuration check for remote plaintext destinations and prohibited disabling of TLS hostname verification. Added comprehensive tests in `TlsClientConfigurationTest`. PR #71 merged into `main` and Issue #61 closed.
4. Implemented Issue #62 (and #66 alignment): Restricted gateway CORS to approved frontend origins (`http://localhost:3000`, `http://127.0.0.1:3000`) without wildcard `*`, redacted internal network exceptions/paths/credentials from HTTP error bodies, added centralized exception handling for unhandled exceptions, and accurately separated warehouse outages from supplier outages in `SupplierController`. PR #72 merged into `main` and Issue #62 closed.
5. Implemented Issue #63: Added shared utility `TcpMessageCodec.isCanonicalUuidV4(String)` and strictly enforced canonical lowercase UUID v4 envelopes at the TCP wire boundary. Added negative unit and real TCP socket tests proving rejection of UUID v1, uppercase UUIDs, whitespace-padded UUIDs, nil UUIDs, and non-text IDs before dispatch or mutation. PR #73 merged and Issue #63 closed.
6. Implemented Issue #65: Enforced strict validation on supplier HTTP 200 responses in `DefaultSupplierHttpClient`. Validated matching SKU, non-blank supplier name, non-negative availableUnits, valid non-negative estimatedRestockDays, and ISO-8601 UTC timestamp format for checkedAt. Added simulator endpoints and 4 new automated tests in `DefaultSupplierHttpClientTest`. PR #74 merged and Issue #65 closed.
7. Implemented Issue #59: In `DefaultStockReservationService.java`, registered `registerPostCommitNotification()` when a stock request is REJECTED and persisted. Maintained zero privacy leaks on NIO stream. Verified post-commit notification publication on rejection, no duplicate notification on replay, no notification on unpersisted validation errors, and rollback immunity in `PostCommitNioNotificationIntegrationTest` and `AtomicStockReservationIntegrationTest`. PR #75 merged and Issue #59 closed.
8. Implemented Issue #60: Added bounded queries (`LIMIT 500`) and deterministic ordering (`ORDER BY created_at ASC, request_id ASC` for requests, `ORDER BY id ASC` for movements) in `JdbcStockRequestRepository` and `JdbcStockMovementRepository`. Guarded outgoing frame size in `StockFulfillmentCommandHandler` and `TcpCommandServer` against `ProtocolInfo.MAX_FRAME_BYTES` (65,536 bytes), emitting contract-compliant `INTERNAL_ERROR` frames rather than truncating rows or abruptly crashing TCP client connections. Added real loopback socket test in `StockFulfillmentIntegrationTest` with 450 bulk requests proving safe error handling and socket preservation.
