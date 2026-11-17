# Mapping to supplied lectures and lab

The group supplied a `net.zip` pack containing lecture/lab PDFs. This map points students to the original course material; the pack is **not embedded** in this starter ZIP to avoid duplicating third-party teaching resources in a Git repository.

| Concept | Lecture/lab files to review | Owner |
| --- | --- | --- |
| Addressing, ports, naming, TCP/IP layers | `Lecture 01 - Conceptual Foundations of Computer Networking.pdf`; `Addressing, Naming and URLs.pdf`; `Lecture 02 - TCP_IP Networking in Java.pdf` | All |
| TCP socket client/server | `TCP Programming.pdf`; `Lab 02.pdf` | M1 |
| Addressing, URI/URL, DNS and HTTP/URLConnection | `Addressing, Naming and URLs.pdf`; `Lab 02.pdf` | M2 |
| Channels, selectors, ByteBuffer | `NIO.pdf` | M3 |
| Threads, synchronization, ExecutorService | `Multithreaded Server Applications.pdf`; `Lab 02.pdf` | M4 |
| TLS, secure sockets and certs | `Secure NP.pdf`; `Secure NP - Java.pdf` | M5 |

UDP remains relevant course background but is not a member-owned MVP feature in design v2. M2 demonstrates real Java HTTP requests to an independently running supplier service, not a Spring controller call. See `NETWORK_FEATURES_V2.md`.

Each member must be able to explain the actual JDK API in their code. A library using sockets internally does not replace direct implementation of that student's assigned lecture concept.

Reference index only: verify lecture specifics with lecturer if the provided PDF edition changes.
