# ThogaMura — network-first feature design v2

**Status:** approved direction for design/backlog revision; implementation is not yet complete. Five member labels are placeholders until the team assigns actual GitHub accounts.

## Genuine operational problem

A **shop/branch terminal** needs to request stock from a **central warehouse**, see changing fulfillment state, and learn whether a supplier can replenish an unavailable item. More than one shop operator can request the same last available units. A network interruption can leave a submitted request's outcome uncertain.

For the coursework demo, run the branch-side Next.js + Spring Boot gateway on one machine and the central Spring Boot Java network server + PostgreSQL on another. They communicate over a real LAN, using a configured DNS name or IP. The system is **LAN-first**, not independently offline-capable; central stock remains authoritative. One optional supplier simulator runs as a separate HTTP service during tests/demos. The same design could serve branch locations over a **secured private network/VPN** after additional security and operational hardening, but this is **not** a production WAN deployment.

## Five non-duplicate, valuable networking features

| Member | Real workflow | Assessed lecture concept | Independent evidence |
| --- | --- | --- | --- |
| M1 — TCP request reliability | Branch gateway sends stock requests to central warehouse and safely retries ambiguous responses | Java `Socket`, `ServerSocket`, TCP framing/streams/timeouts | Two distinct JVMs; split/merged frames; disconnect after commit; same-key retry causes no second reservation |
| M2 — Supplier stock inquiry | Warehouse checks a configured supplier service for availability of an unavailable SKU; gives staff information for manual follow-up | Java `URI`, `URL`, `HttpURLConnection`/`HttpsURLConnection`, DNS/HTTP status/timeout handling | Real HTTP request to independent mock supplier; 200, 404, 503, timeout and malformed response; no invented availability |
| M3 — NIO change feed | Central warehouse pushes small change signals to multiple **branch gateways** so browsers refresh request status promptly | `ServerSocketChannel`, `SocketChannel`, `Selector`, `ByteBuffer` | 10 gateway-like Java subscribers, one selector loop, partial-write, slow consumer, disconnect/reconnect and UI refetch |
| M4 — Concurrent reservations | Two shops compete for remaining stock; exactly the available amount can be reserved | `ExecutorService`, bounded tasks, concurrent request handling, PostgreSQL row/conditional update transactions | Two concurrent 8-of-10 requests; at most one accepted, DB constraints hold, duplicate replay and dispatch exactly once |
| M5 — Secure command transport | Branch and warehouse exchange commands on a protected channel and verify server identity | JSSE `SSLSocket`, `SSLServerSocket`, truststores, X.509 certificates | Valid handshake succeeds; wrong/untrusted server identity fails closed; no plaintext command path or secrets in Git |

**Do not claim** the use of TCP *alone* gives exactly-once business processing: application-level UUIDs + persistent command receipts + transactional writes do. **Do not claim** raw NIO is required at five clients: it is the required lecture concept, applied to a real cross-process change-distribution need.

## Architecture and responsibilities

```text
Shop browser (Next.js) <--HTTP/SSE--> Spring Boot branch gateway
                                               |
                                  JSSE/TLS + Java TCP commands [M1/M5]
                                               |
                                    Central Java network server
                                      |                  |
                                 PostgreSQL       NIO change signal [M3]
                                      |                  |
                               atomic stock [M4] -------> branch gateways -> browser SSE refresh

Central network server -- HTTPS/HTTP GET [M2] --> configured supplier service
                                                  (local mock in coursework demo)
```

The central network server is the only inventory DB writer. The gateway is the browser boundary, not a second inventory authority. Java NIO change feed carries only non-secret version/invalidation information; clients re-fetch truth over authenticated commands. It is only eligible for a private, firewall-restricted demo LAN. **Never expose port 7444 over the public internet.** A future WAN release requires TLS-protecting this feed (e.g., an authenticated VPN or a properly implemented TLS transport) and human authentication/authorization for every mutation.

No UDP discovery: a configured host or hostname is more appropriate for an installed gateway. A standard web browser cannot use Java UDP sockets. No separate mobile/desktop agent, offline mutation queue, supplier purchasing, automatic reorder or multi-warehouse stock ownership in the MVP.

## Scope boundary and supplier connector

- Supplier availability is **read-only, advisory and optional**. It never changes central warehouse quantities and never promises a purchase.
- Use one configured supplier endpoint (e.g., local demo `http://127.0.0.1:8090`); the mock service runs separately to prove real HTTP networking.
- Expose `GET /api/v1/suppliers/availability?sku=ITEM-008` through the branch gateway. Gateway delegates to central network server `GET_SUPPLIER_STOCK`; central uses Java URL/HTTP connection to supplier.
- Timeouts, status codes, malformed JSON and supplier outage must be visible as `UNKNOWN/UNAVAILABLE`, **not as zero stock**.
- Never let a user supply an arbitrary target URL. Allowlist/configure trusted supplier base URLs to avoid SSRF. Prefer HTTPS with normal certificate checks outside localhost.
- If a real supplier has no documented API, the demo proves the connector **pattern only**; no claim of live commercial integration.

## Central inventory correctness

Physical `on_hand=10`; accepting a request for 8 sets `reserved=8`, thus `available=2`. Picking does not change quantities. Dispatch decrements both on-hand and reserved by 8 and appends one movement. Rejection/cancellation releases reservations if added later. Conflicting requests cannot over-reserve. Server restarts preserve receipts and stock; network or notification disconnects must not corrupt stock.

## Proof criteria: answer the lecturer's "why?"

- **M1:** The branch and warehouse run on different machines. Why do we need framing, reconnect and an idempotency key when transport fails mid-request?
- **M2:** When central stock is insufficient, how can staff check an external supplier over an actual HTTP connection without inventing an answer when it is offline?
- **M3:** How do several branch gateways receive a central status-change hint without polling, and how are partial writes and missed hints handled?
- **M4:** What prevents two simultaneous clients from reserving more than exists, even with several server worker threads?
- **M5:** How can a branch verify the central warehouse server and reject a forged/untrusted endpoint?

## Explicit limitations

- This is a coursework MVP, **not a production inventory platform**.
- The supplier simulator is not a commercial supplier integration; only the HTTP adapter is real.
- The NIO feed is a restricted private-LAN mechanism and not suitable for an untrusted network without additional TLS/authentication.
- Human login, roles, branch tenant isolation, high availability, durable offline queue, auditing of all human actions and secure public deployment are **future requirements**.
- Verify five individual workload choices with the lecturer before assigning people.
