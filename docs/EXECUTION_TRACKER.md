# ThogaMura Autonomous Execution Tracker

**Start Date:** 2026-11-21  
**Lead Coordinator / Engineer:** Autonomous Lead Software Engineer & GitHub Repository Maintainer  
**Target:** 100% verification and delivery of remaining actionable software backlog.

---

## Issue Execution Matrix

| Issue | Title | Priority | Status | Branch / PR | Verification / Evidence |
|---|---|---|---|---|---|
| **#61** | [M5][P1] Fail closed for remote plaintext commands and disabled TLS hostname checks | P1 | IN PROGRESS | `fix/m5-fail-closed-remote-plaintext-61` | `TlsClientConfigurationTest` (6/6), `TlsTruststoreHostnameVerificationTest` (5/5), `TlsTcpCommandClientIntegrationTest` (9/9) passing |
| **#62** | [M5][P1] Restrict gateway CORS and redact internal network errors | P1 | PENDING | | |
| **#63** | [M1][P1] Enforce canonical UUID v4 request IDs at TCP boundary | P1 | PENDING | | |
| **#65** | [M2][P1] Reject mismatched or incomplete supplier HTTP 200 responses | P1 | PENDING | | |
| **#59** | [M4][P2] Notify branch gateways when a stock request is rejected | P2 | PENDING | | |
| **#60** | [M4][P2] Bound request and movement reads to 64 KB TCP limit | P2 | PENDING | | |
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
3. Implemented Issue #61: Fail-closed configuration check for remote plaintext destinations and prohibited disabling of TLS hostname verification. Added comprehensive tests in `TlsClientConfigurationTest`.
