# Five distinct member workloads

**Replace Member 1–5 with actual names at kickoff.** Each member owns source, tests and a 60–90s live demonstration of one distinct lecture networking concept. UI, docs and report writing are shared; no member's sole assessed work is frontend polishing.

## Member 1 — TCP commands and reliability

- **Lecture concept:** `java.net.Socket`, `ServerSocket`, streams, TCP lifecycle, timeouts and framing.
- **Build:** newline-delimited JSON codec; command dispatcher and gateway client; request/response correlation, retry with same requestId, reconnect handling.
- **Interfaces:** `NETWORK_PROTOCOL_V1.md` envelope, Member 4 reservation service, Member 5 TLS socket factory.
- **Owned packages:** `network-server/.../transport/tcp` and `api-gateway/.../network/tcp`.
- **Demo:** send request over real socket; force lost response; retry exact requestId; prove no second reservation.
- **Tests:** partial frame, merged frames, oversized frame, interrupted connection, timeout, retry with same ID.
- **Definition of done:** real two-JVM call, not a Spring method invocation.

## Member 2 — UDP discovery

- **Lecture concept:** `DatagramSocket`, `DatagramPacket`, `InetAddress`, ports, receive timeout.
- **Build:** network server discovery responder; gateway discovery client; nonce matching, bounded datagram parsing, manual IP fallback and status UI integration.
- **Interfaces:** discovery packet in `NETWORK_PROTOCOL_V1.md`; returned port/host passed to Member 1 and Member 5.
- **Owned packages:** `network-server/.../discovery` and `api-gateway/.../network/discovery`.
- **Demo:** start with no configured server address; discover over LAN, then block UDP/broadcast and demonstrate manual fallback.
- **Tests:** nonce mismatch, timeout, corrupted packet, multiple responders, port override.
- **Security:** discovered endpoint is untrusted until TLS identity verification.

## Member 3 — Java NIO notifications

- **Lecture concept:** `ServerSocketChannel`, `SocketChannel`, `Selector`, `SelectionKey`, `ByteBuffer`, non-blocking read/write.
- **Build:** one selector loop for multiple subscribers; bounded write queues; only `CHANGED/version` frames; gateway subscriber; SSE invalidation to browser.
- **Interfaces:** feed in `NETWORK_PROTOCOL_V1.md`, HTTP SSE in `HTTP_API_V1.md`.
- **Owned packages:** `network-server/.../notifications` and `api-gateway/.../network/notifications`.
- **Demo:** 10+ connected clients receive change after a committed request; disconnect some clients; server stays stable.
- **Tests:** partial writes, slow subscriber, disconnect, multiple clients, missed event then authoritative refetch.
- **Security:** no confidential stock or credentials on this unencrypted feed.

## Member 4 — Multithreading and atomic inventory

- **Lecture concept:** `ExecutorService`, worker pools, synchronized/shared state, races and concurrent requests.
- **Build:** bounded request execution; `product_stock` locking/conditional update; idempotent request persistence; PICKED and DISPATCHED transactional transitions; movement history.
- **Interfaces:** command handler called by Member 1; persists via PostgreSQL. Member 3 gets notification **only after commit**.
- **Owned packages:** `network-server/.../concurrent`, `.../inventory`, `.../persistence`.
- **Demo:** 10 items available; two parallel requests ask 8 each; at most one succeeds; retry does not reserve twice.
- **Tests:** barrier-synchronized race, duplicate requestId, conflicting payload, restart replay, double dispatch, invariant checks.
- **Invariants:** `available = on_hand - reserved >= 0`; movement at most once per request.

## Member 5 — Secure Java sockets and TLS

- **Lecture concept:** `SSLServerSocket`, `SSLSocket`, JSSE, certificates, TLS handshake, truststores.
- **Build:** secure command listener/client configuration, trust and hostname validation, test certificate generation docs, optional gateway mutual TLS, secure failure reporting.
- **Interfaces:** wraps Member 1's existing stream/codec without rewriting command semantics.
- **Owned packages:** `network-server/.../security` and `api-gateway/.../network/security`.
- **Demo:** valid trusted TLS connection succeeds; untrusted/mismatched certificate or client identity is rejected; no plaintext fallback.
- **Tests:** handshake success, invalid CA, wrong server identity, missing certificate (if mTLS enabled), sensitive-data log review.
- **Security:** private keys remain outside repository; do not disable verification for convenience.

## Shared work and fairness

- Frontend route/UI components, README, continuous integration, database deployment and report are **shared/reviewed**, not replacement network concepts.
- Each student should author and understand their own implementation. Two peer reviews for transport/security/concurrency integrations.
- Use branches such as `feat/m1-tcp-framing`, `feat/m2-udp-discovery`; PRs target main after narrow tests.
- Students can support one another but must retain clear assessed source and demonstration ownership.

## Dependency order

1. Team freezes protocol and API shapes.
2. M1 builds plain TCP transport loop with one PING-like round trip; M4 builds transactional domain service with unit tests.
3. M2 and M3 build independently against frozen ports and version signal; M5 prepares certificate materials and can wrap M1's transport as soon as it exists.
4. Gateway wire adapters integrate with HTTP routes and Next pages.
5. All five rehearse their failure-mode demos on two LAN devices.
