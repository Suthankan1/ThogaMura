# Group demo — v2 (target 8–10 minutes)

**Set-up:** The target demonstration uses a branch laptop running Next.js + gateway and a warehouse laptop running the central Java network server + PostgreSQL. The supplier mock runs independently on a specified demo port. Synthetic ITEM-007=10 and ITEM-008=0; configured warehouse host, no auto-discovery. No real API credentials or private keys in screenshots.

**Current evidence status:** Trusted TLS, certificate rejection, separate-JVM transport, and the stock flow have been verified locally on one laptop using loopback. The physical two-laptop LAN rehearsal is still pending and must not be presented as completed.

| Sequence | Presenter | Demo | Lecture concept and business value |
| --- | --- | --- | --- |
| 1 | M5 | Trusted branch-to-central TLS handshake passes; wrong-name/untrusted cert fails | JSSE/identity protects stock commands |
| 2 | M1 | Browser submits 8 units over TLS/TCP; cut response then replay same UUID | Raw Java Socket streams/framing + ambiguous-response retry |
| 3 | M4 | Two simultaneous 8-of-10 requests, one accepted; single dispatch movement | ExecutorService and PostgreSQL transaction |
| 4 | M3 | Ten simulated branch-gateway NIO subscribers receive small change hints; reconnect triggers UI refetch | Selector, ByteBuffer and partial I/O |
| 5 | M2 | Unavailable ITEM-008 triggers supplier lookup; real HTTP stub returns availability; then 503/timeout shows unknown | URI/URL/HttpURLConnection, status/timeouts |
| 6 | Everyone | Warehouse PICKED -> DISPATCHED; shop page updates, history survives restart | Full integrated workflow |

Each presenter must show their own source, a real network call, and a failing case they understand. NIO is not proof of durable notifications; supplier mock is not a real supplier service. All UI states come from authoritative data, never fake demo values.

**Fallback:** For demo network issues, use a known private hotspot and manually configured host; do not weaken TLS verification. Pre-recorded logs may support explanations but cannot be presented as a live successful test.

**Before claiming the live LAN demo:** Complete the two-device setup, verify the warehouse certificate SAN contains the warehouse LAN IP/DNS name, confirm the gateway trusts the public CA bundle, and capture both a successful TLS command and a rejected wrong-name or rogue-CA attempt. Until then, describe the evidence as local separate-JVM verification only.
