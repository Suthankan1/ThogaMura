# Security, deployment and known limits (v2)

**Coursework prototype, not production-ready.** It runs across a trusted private demo LAN. The branch gateway's central **command** connection uses Java JSSE TLS and validates server certificate + hostname. Only synthetic data is used in demos.

- Port 7443: TLS-only `SSLSocket`/`SSLServerSocket` with trusted CA identity; never silently downgrade to plaintext; generated private keystores stay outside Git.
- Port 7444: custom Java NIO feed is **not authenticated/encrypted in MVP**. It must be restricted via firewall/private LAN to gateway hosts and carry only `CHANGED/version`. **Never publish it on untrusted WLAN or public Internet**. Add an authenticated TLS feed or secured VPN before any WAN deployment.
- Port 8080 / Next UI: local demonstration HTTP only. Browser-to-gateway HTTPS and human login/authorization with roles are required for real deployment. TLS to the central server does not prove the shop user is authorized.
- Port 8090: mock supplier endpoint for test/demo; `HttpURLConnection` must have connect/read timeouts, response-size bounds, strict DTO parsing and configured endpoint allowlist; use HTTPS and normal certificate validation for actual external APIs.
- Browser cannot perform raw Java UDP/TCP sockets. UDP broadcast discovery is no longer in v2; warehouse host configured via IP/DNS.
- NIO events are lossy hints, never the system of record. Browser refetches after missed updates and page focus.
- Do not log user credentials, full stock payloads, certificate private keys, auth tokens or supplier secrets.
- Reusing a request UUID with different body must fail; retries cannot double-reserve. Database concurrency controls protect inventory against simultaneous workers.
- No durable offline branch queue, multi-warehouse sync, automatic supplier order, supplier account integration, high availability or audit-compliant production setup yet.

If any team member changes these assumptions, add an ADR, revise contracts/tests and seek group approval before deploying outside the demo LAN.
