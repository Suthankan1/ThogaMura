# Security, deployment and known limits (v2)

- **Status:** **FROZEN** (2026-11-18)
- **Reference ADRs:** [ADR-004 (TLS Policy)](./decisions/DECISION_LOG.md#adr-004--tls-identity-policy-accepted), [ADR-006 (Topology & Transport)](./decisions/DECISION_LOG.md#adr-006--remove-udp-discovery-and-define-practical-network-features-2026-11-17-accepted-as-v2-design), [ADR-007 (Contracts Freeze)](./decisions/DECISION_LOG.md#adr-007--shared-network-protocol-v1-http-api-v1-and-security-contracts-freeze-2026-11-18-accepted)

> [!WARNING]
> **Coursework prototype, not production-ready.** This system is designed and validated for demonstration across a trusted private demo LAN or loopback interfaces. It uses synthetic test data (`ITEM-007`, `ITEM-008`).

---

## 1. Port Architecture & Transport Security Boundaries

| Port | Protocol / Transport | Owner | Encryption & Authentication Policy |
| :--- | :--- | :--- | :--- |
| **7443** | Custom TCP / JSSE TLS | Member 1 & Member 5 | **Mandatory Server-Authenticated TLS (ADR-004):** `SSLServerSocket` / `SSLSocket` validating X.509 certificate trust chain and Subject Alternative Name (SAN). Plaintext fallback is strictly prohibited. Keystores (`.p12`/`.jks`) stay outside Git. |
| **7444** | Java NIO Channel | Member 3 | **Unencrypted Private-LAN Feed:** Bound to loopback (`127.0.0.1`) by default. Binding to non-loopback requires explicit operator approval (`thogamura.notifications.allow-non-loopback=true`) and CIDR allowlisting. **Strictly prohibited on public WAN/Internet**. Carries only version-only invalidation hints (`{"v":1,"type":"CHANGED","version":<n>}`). |
| **8080** | HTTP / SSE | Member 2 & Member 3 | **Local Demonstration HTTP:** Browser-to-gateway interface. Localhost development only. Production deployment would require public HTTPS and human authentication/RBAC. |
| **8090** | External Mock HTTP | Member 2 | **Supplier Simulator:** Read-only outbound `HttpURLConnection` with strict connect (3s) and read (5s) timeouts, 16 KB payload bounds, and configured allowlisted base URL. |

---

## 2. Cryptographic & Credential Management Rules

1. **No Secrets in Source:**
   - Private keys, keystores, `.env` files, and database passwords must **never** be committed to version control.
   - `.gitignore` explicitly excludes `*.p12`, `*.jks`, `*.key`, `*.crt`, `.env`, and `.env.local`.
2. **TLS Verification Enforcement:**
   - No `TrustAllStrategy` or disabled certificate checkers are permitted in production paths.
   - Hostname verification is strictly enforced by the gateway's `SSLSocketFactory`.
   - Untrusted, self-signed (without custom CA in truststore), expired, or name-mismatched certificates immediately fail closed.
3. **Mutual TLS (mTLS) Position:**
   - Server-authenticated TLS is the frozen MVP standard.
   - Mutual TLS (verifying gateway client certificates on the warehouse server) is supported as an optional advanced demonstration extension, but is not required for MVP baseline grading.

---

## 3. Data Protection & Privacy Safeguards

1. **Information Leakage Prevention:**
   - Passwords, database connection strings, and internal stack traces are suppressed in all client responses and wire error envelopes.
   - The unencrypted NIO notification feed transmits **only** `{"v":1,"type":"CHANGED","version":<n>}\n`. It is strictly forbidden from including SKUs, stock counts, customer details, or operator tokens.
2. **Log Sanitization:**
   - Handlers and decoders must not log unmasked payload credentials, operator identities, or full database dumps.
   - High-volume frame debug logging must be guarded behind `logger.isDebugEnabled()`.

---

## 4. Idempotency & Database Integrity Constraints

1. **Deterministic Request Fingerprinting:**
   - All mutating commands (`REQUEST_STOCK`, `MARK_PICKED`, `MARK_DISPATCHED`) must supply a UUID v4 `requestId`.
   - PostgreSQL stores `(command_request_id, command_type, payload_fingerprint, result_json, created_at)` in `command_receipts` in the same transaction as the inventory update.
   - Replaying identical `requestId` and payload replays the recorded outcome without mutating inventory a second time.
   - Conflicting payloads with an existing `requestId` return `DUPLICATE_ID_CONFLICT` (`409 Conflict`).
2. **Stock Invariant Enforcement:**
   - Invariant: `available = on_hand - reserved >= 0`.
   - Table constraint: `CONSTRAINT ck_reserved_not_over_on_hand CHECK (reserved <= on_hand)`.
   - Database row locks (`SELECT FOR UPDATE`) or atomic conditional updates guarantee that concurrent worker threads cannot over-reserve stock.

---

## 5. Explicit Coursework Non-Goals & Limitations

The following capabilities are acknowledged non-goals for this university assignment:
- **No Production Access Control:** The web browser interface does not implement individual human user login, OAuth2, or multi-tenant authorization.
- **No Public WAN Exposure:** The warehouse server must not be deployed directly to the public internet without an authenticated VPN or TLS-protected notification feed.
- **No Durable Offline Branch Queue:** Disconnected branch gateways cannot queue offline mutations; central PostgreSQL remains the sole authoritative inventory authority.
- **No Commercial Purchasing:** The supplier connector is an advisory availability check; it does not place real purchase orders or handle payments.
- **No UDP Auto-Discovery:** Deployed warehouse hostnames/IPs are configured via environment properties (`thogamura.warehouse.host`), reflecting realistic branch-to-datacenter network topology.
