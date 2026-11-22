# ThogaMura validation status

## 23 November 2026 stabilization

Verification used Java 21, Node.js 24, isolated PostgreSQL data and separate gateway, warehouse and supplier processes on loopback. The deployed app retained the frozen Java wire v1 command and notification formats.

| Check | Observed result |
| --- | --- |
| Maven `clean verify` | 353 tests passed across the three modules. A targeted run also passed both concurrent dispatch variants after adding same-key coverage. The full run included real TCP framing, JSSE trust and hostname rejection, Java NIO fanout, independent supplier HTTP and transactional PostgreSQL. The suite uses its own Testcontainers instance. |
| UI checks | 44 unit tests passed. ESLint and generated-route TypeScript passed. The production webpack build succeeded for all seven user routes. |
| Browser smoke | Headless Chromium intercepted a reservation reply after commit. Reload and retry reused the UUID and reserved once. A second tab received a version hint through Next.js, and the operator picked the request, lost the dispatch response, and retried it with one recorded movement. Supplier availability and outage were shown correctly. The tested screens fit a 375 px viewport. |
| HTTP smoke | The TLS stack returned 400 for a fractional quantity, 404 for an unknown SKU and 409 for dispatch before pick. Reservation, pick and dispatch repeated their receipts. Dispatch reduced on-hand by one and recorded one movement. |
| Local runner | Start, status, fixture rerun, repeated start and stop passed with custom ports. The PONG readiness check reached the warehouse over TLS. Startup rejected a separate port listener and shutdown left that listener reachable. The temporary services and disposable database were removed after verification. |

The default Turbopack build hit a local worker-port restriction during the audit. The production stack used webpack. The GitHub Actions browser job is configured for subsequent runs. Physical firewall tests, screen-reader testing and the two-device rehearsal remain open.

## Workspace and extended network verification

All five members approved ADR-008–010. The protected workflow now runs on the versioned TLS profile. The final Maven verification passed 360 Java tests. The frontend passed 44 unit tests, lint, generated-route type checking and a production webpack build. The database tested a V2-to-V3 upgrade with legacy rows and a populated dispatch ledger. Request pagination tests covered tied timestamps, inserts between pages, cross-branch scope and long UTF-8 display names.

| Check | Observed result |
| --- | --- |
| Isolated private bridge | Separate gateway, warehouse and supplier JVMs reached a private PostgreSQL network. Forty-eight TLS-backed commands at concurrency 12 had p50 109.0 ms and p95 184.6 ms. All twelve SSE streams received version-only hints. Two competing reservations produced one acceptance and twelve concurrent dispatch calls recorded one movement. An unapproved peer received no NIO data. |
| Link failure and stalled reader | An isolated bridge link removal was detected after 16 seconds. Reattachment restored the NIO subscriber and TLS command path. A deterministic Java test held one SSE writer and confirmed a healthy peer still received its next hint. |
| Protected HTTP/TLS | The warehouse enforced branch and role scope. Exact lookup, cursor traversal and scope mismatch, CSRF rejection, cancelled balance restoration, cancel/pick contention, eight concurrent receiving replays and the movement ledger passed. |
| Protected browser | Chromium confirmed same-origin cookies, reloadable request detail, cancellation and cross-branch denial. It dropped a receiving response after commit, reloaded, reused the command key, and confirmed one receiving movement. The tested browser screen fit a 390 px viewport. |

The private bridge run used one host. The actual two-device LAN, host firewalls, student-owned live evidence and lecturer sign-off still need verification. The ZIP and report are ready for group review. The designated student submits the verified bundle when the required identifying and physical evidence is complete.

[Setup and verification commands](SETUP.md) · [Approved decisions](decisions/DECISION_LOG.md#workspace-decisions-accepted-by-the-group) · [Measured network evidence](../reports/evidence/segmented_lan_closeout.txt)
