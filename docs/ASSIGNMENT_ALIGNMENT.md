# Assignment 02 compliance — v2

| Assignment requirement | Proposed implementation | Evidence to present |
| --- | --- | --- |
| Java networking with `java.net` | M1 real TCP client/server between gateway and warehouse; M2 Java URL/HTTP supplier connector | Two-process socket traces/tests and separate supplier HTTP server tests |
| Java NIO concepts | M3 actual selector-based network subscriber service | `ServerSocketChannel`, `Selector`, `SocketChannel`, `ByteBuffer`, partial-write demonstration |
| Individually distinct member concepts | M1 TCP, M2 Java URL/HTTP & addressing, M3 NIO, M4 threading/concurrency, M5 JSSE/TLS | Named author and 1 success + 1 failure demonstration per student |
| Application of choice | Branch-to-central warehouse stock requests, picker workflow, advisory supplier availability | Browser workflow + authoritative database outcomes |
| Complete project + report PDF in one ZIP | Repository source + final PDF report under `reports/final/` and packaging checklist | Clean-clone build, ZIP integrity and one Moodle group submission |

**Lecture scope:** Addressing, Naming and URLs + Lab 02 cover URL/URI/HTTP programming. M2 intentionally **replaces low-value UDP auto-discovery**, which the assignment did not mandate. Lecturer approval of this five-way split is still needed.

**Evidence rules:** Don't rely on Spring Boot REST alone to prove `java.net.Socket`. M1 must show authored raw Java sockets. Don't use Spring SSE alone to prove NIO; M3 must show an actual selector/channel implementation. M4 must test real database concurrency, not just `synchronized`. M5 must reject an untrusted certificate. M2 must make real network HTTP calls, not return hard-coded supplier values in the connector.

**Limits:** coursework MVP on a private LAN; no production role-based authentication, WAN-secure NIO feed, actual commercial supplier integration or offline queue. See `NETWORK_FEATURES_V2.md`.
