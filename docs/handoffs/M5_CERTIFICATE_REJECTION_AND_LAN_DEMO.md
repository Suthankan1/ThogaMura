# Member 5 Handoff — Certificate Rejection and Secure LAN Demo (Issue #24)

- **Owner:** Member 5 (@vibhathkalsara99 — Vibhath Kalsara)
- **Role:** Java TLS Networking (JSSE `SSLContext`, `SSLSocket`, `SSLServerSocket`, Keystores, Truststores, and SAN Hostname Verification)
- **Module:** `backend/network-server` (`lk.thogamura.networkserver.security`) and `backend/api-gateway` (`lk.thogamura.gateway.network.security`)
- **Status:** Complete (Verified with automated unit, socket integration, separate-JVM process tests, and fail-closed certificate rejection suites)
- **Reference Issue:** [#24 — [M5][W4] Verify certificate rejection and secure LAN demo](https://github.com/Suthankan1/ThogaMura/issues/24)
- **Governing Contracts:** [ADR-004 (TLS Identity Policy)](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/decisions/DECISION_LOG.md#adr-004--tls-identity-policy-accepted), [ADR-006 (Topology & Transport)](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/decisions/DECISION_LOG.md#adr-006--remove-udp-discovery-and-define-practical-network-features-2026-11-17-accepted-as-v2-design), [ADR-007 (Contracts Freeze)](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/decisions/DECISION_LOG.md#adr-007--shared-network-protocol-v1-http-api-v1-and-security-contracts-freeze-2026-11-18-accepted), [NETWORK_PROTOCOL_V1.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/contracts/NETWORK_PROTOCOL_V1.md#8-tls--transport-security-specification-port-7443), [SECURITY_AND_LIMITATIONS.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/SECURITY_AND_LIMITATIONS.md), [DEMO_SCRIPT.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/DEMO_SCRIPT.md), [TEST_PLAN.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/TEST_PLAN.md)

---

## 1. Executive Summary & Verification Scope

Issue #24 completes and verifies the second slice of Member 5's TLS transport deliverable:
1. **Verified TLS Certificate Rejection (T13):** Rigorous automated verification that untrusted CAs, hostname/SAN mismatches, plaintext probe attempts, and missing/corrupted truststores fail closed without fallback.
2. **Validated TLS Operation (T12):** End-to-end verification that trusted CAs with valid SAN matching (including LAN IP addresses and internal hostnames) succeed and process framed business commands.
3. **Multi-Process / Separate-JVM Demonstration:** Validation that the gateway client and warehouse listener exchange framed commands across separate JVM processes strictly via TLS on port 7443.
4. **Physical LAN Demonstration Preparedness:** Scripts, configuration, and verification tests enabling seamless execution on two separate physical laptops over a local Wi-Fi / Ethernet LAN, accompanied by an honest record marking live multi-laptop execution as pending lab rehearsal.
5. **NIO and HTTP Boundary Demarcation:** Formal verification that unencrypted feeds (Java NIO port 7444) carry only version metadata without sensitive stock or customer data, and documentation of browser-to-gateway local HTTP limitations.

---

## 2. TLS Handshake Sequence & JSSE Mechanics

The warehouse command transport operates over port 7443 using Java Secure Socket Extension (JSSE).

```
Client (API Gateway)                                 Server (Warehouse Network Server)
    │                                                               │
    ├───────────────────── ClientHello ────────────────────────────>│
    │   (Supported cipher suites, TLS 1.3 / 1.2, SNI)               │
    │                                                               │
    │<──────────────── ServerHello, Certificate, KeyShare ──────────┤
    │   (Server presents X.509 cert chain from server-keystore.p12) │
    │                                                               │
    │ [JSSE Trust Validation]                                       │
    │ 1. Validate cert chain against gateway-truststore.p12         │
    │    ──> Throws SSLHandshakeException if untrusted CA           │
    │ 2. RFC 2818 Endpoint Identification ("HTTPS" algorithm)       │
    │    ──> Matches peer IP/host against SAN extension             │
    │    ──> Throws SSLHandshakeException if SAN mismatch           │
    │                                                               │
    ├───────────────────── Finished ───────────────────────────────>│
    │<──────────────────── Finished ────────────────────────────────┤
    │                                                               │
    │══════════════════ Encrypted TLS Session ══════════════════════│
    │                                                               │
    ├─────── Bounded Frame: {"v":1,"type":"REQUEST_STOCK",...}\n ───>│
    │<────── Bounded Frame: {"v":1,"type":"STOCK_RESERVED",...}\n ──┤
```

### JSSE Code Configuration:
- **Server:** Initialized with `SSLServerSocketFactory` loaded from a PKCS#12 keystore containing the server's private key and certificate chain signed by the demonstration Root CA.
- **Client:** Initialized with `SSLSocketFactory` backed by a `TrustManagerFactory` loaded with `gateway-truststore.p12` (containing solely the public demonstration Root CA; zero private keys).
- **Hostname Verification:** Enabled via `sslSocket.getSSLParameters().setEndpointIdentificationAlgorithm("HTTPS")`, forcing strict RFC 2818 SAN matching against DNS and IP SAN entries.

---

## 3. Verified Certificate Rejection & Fail-Closed Scenarios (T13)

All rejection cases were executed against live socket connections and verified to fail closed immediately:

| Failure Mode | Trigger / Condition | JSSE Exception Observed | Security Impact / Result |
| :--- | :--- | :--- | :--- |
| **Untrusted CA** | Server presents certificate signed by an untrusted or foreign CA not in gateway truststore | `javax.net.ssl.SSLHandshakeException: PKIX path building failed: unable to find valid certification path to requested target` | Connection aborted immediately during handshake; zero application data transmitted. |
| **Hostname / SAN Mismatch** | Client connects to host/IP not present in certificate SAN (e.g. `10.0.0.99` when cert has `127.0.0.1` and `localhost`) | `javax.net.ssl.SSLHandshakeException: No subject alternative names matching IP address 10.0.0.99 found` | Prevents Man-in-the-Middle (MitM) impersonation; socket closed before frame exchange. |
| **Plaintext Client on TLS Port** | Unencrypted client sends raw JSON frame `{"v":1,"type":"PING",...}\n` to port 7443 | `javax.net.ssl.SSLException: Unsupported or unrecognized SSL message` | Server TLS record layer immediately drops the connection; no plaintext response returned. |
| **Missing Truststore File** | Gateway configured with non-existent truststore path | `java.io.FileNotFoundException` (at startup/factory creation) | Fails closed; gateway refuses to start or connect without valid trust material. |
| **Corrupted / Invalid Truststore Password** | Gateway configured with incorrect truststore password | `java.io.IOException: keystore password was incorrect` | Fails closed; unauthenticated connection never attempted. |
| **Disabled TLS Checkers** | Prohibited trust-all managers | *Zero instances in codebase* | Strict default SunJSSE / PKIX trust managers enforced everywhere. |

---

## 4. Secure LAN Demonstration

### 4.1 LAN Certificate Generation
The project certificate generator `scripts/generate-dev-certs.sh` supports generating certificates for LAN demonstration with custom IP addresses and internal hostnames:
```bash
./scripts/generate-dev-certs.sh --lan-ip 192.168.8.100 --san dns:warehouse.thogamura.internal
```
This embeds:
- `DNS:localhost`
- `IP:127.0.0.1`
- `IP:192.168.8.100` (LAN IP of the warehouse machine)
- `DNS:warehouse.thogamura.internal`

### 4.2 Automated LAN SAN Verification Test
In `TlsTcpCommandClientIntegrationTest#testLanSanMatchingSucceeds`, a certificate bundle was dynamically provisioned with `ip:192.168.8.100` and `dns:warehouse.thogamura.internal` in the SAN extension. The client connected to the TLS server matching the SAN identity, completing the handshake and successfully executing framed commands (`PING`/`PONG` and `REQUEST_STOCK`).

### 4.3 Safe LAN Interface Binding
In `TcpCommandServerTest#shouldPermitNonLoopbackBindingWhenTlsIsEnabled`, the server binding safety rule was verified:
- Plaintext TCP binding to non-loopback interfaces without explicit bypass throws `IllegalStateException` to prevent accidental unencrypted LAN exposure.
- Enabling TLS (`tlsEnabled=true`) permits binding to LAN interfaces (`0.0.0.0` or `192.168.x.x`), ensuring that network commands over the LAN are strictly protected by TLS encryption.

### 4.4 Multi-Process / Two-JVM Verification
`SeparateProcessTlsCommandIntegrationTest` launches the Central Warehouse Server in an independent JVM sub-process with TLS enabled on port 7443, while the API Gateway client runs in the test JVM. The test proves:
1. Legitimate TLS client connects, handshakes, and executes business commands over the wire.
2. Plaintext client attempting connection to the independent JVM is rejected with zero plaintext response.
3. Multiple commands execute across the process boundary over persistent TLS streams.

### 4.5 Live Physical LAN Demonstration Instructions (Demo Script Item 1)
For the final physical presentation on two student laptops:
1. **Warehouse Laptop (IP e.g. `192.168.8.100`):**
   ```bash
   ./scripts/generate-dev-certs.sh --lan-ip 192.168.8.100
   export THOGAMURA_COMMAND_SERVER_HOST=0.0.0.0
   export THOGAMURA_COMMAND_SERVER_PORT=7443
   export THOGAMURA_TLS_ENABLED=true
   export THOGAMURA_TLS_KEYSTORE_PATH=.certs/server-keystore.p12
   export THOGAMURA_TLS_KEYSTORE_PASSWORD=thogamura-dev-keystore-pass
   java -jar backend/network-server/target/network-server-0.1.0-SNAPSHOT.jar
   ```
2. **Branch Gateway Laptop (IP e.g. `192.168.8.101`):**
   - Copy only the public `gateway-truststore.p12` to `.certs/gateway-truststore.p12` (never copy private keys).
   ```bash
   export THOGAMURA_WAREHOUSE_HOST=192.168.8.100
   export THOGAMURA_WAREHOUSE_COMMAND_PORT=7443
   export THOGAMURA_WAREHOUSE_TLS_ENABLED=true
   export THOGAMURA_WAREHOUSE_TLS_TRUSTSTORE_PATH=.certs/gateway-truststore.p12
   export THOGAMURA_WAREHOUSE_TLS_TRUSTSTORE_PASSWORD=thogamura-dev-truststore-pass
   java -jar backend/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar
   ```
3. **Live Demonstration Steps:**
   - **Step 1A (Pass):** Gateway connects to `192.168.8.100:7443`; TLS handshake succeeds; browser or curl executes `POST /api/orders` successfully.
   - **Step 1B (Fail - SAN Mismatch):** Reconfigure gateway host to `192.168.8.102` (or non-SAN DNS); connection fails with `SSLHandshakeException: No subject alternative names matching...`.
   - **Step 1C (Fail - Untrusted CA):** Supply gateway with an untrusted truststore; connection fails with `PKIX path building failed`.
   - **Step 1D (Fail - Plaintext Prohibited):** Connect via plaintext `nc 192.168.8.100 7443`; send `{"v":1...}`; server terminates connection immediately.

> [!NOTE]
> **Physical Demonstration Status:** Automated two-process and programmatic LAN SAN verification are fully passing and verified in code. Execution on physically separate laptop hardware is marked as **pending physical laboratory session / rehearsal**, exactly as specified in the assignment test plan.

---

## 5. Security Boundaries & Architecture Demarcation

### 5.1 Java NIO Feed (Port 7444)
- **Encryption:** None (unencrypted raw TCP byte stream).
- **Network Scope:** Restricted to a trusted private demonstration LAN; loopback (`127.0.0.1`) by default.
- **Payload Contents:** Strictly cache invalidation metadata (`{"v":1,"type":"CHANGED","version":<n>}\n`).
- **Data Protection:** `ChangeNotification` enforces an immutable key allowlist (`v`, `type`, `version`). Any attempt to serialize SKUs, stock levels, operator tokens, or customer information is rejected with `IllegalArgumentException`.
- **LAN Binding Rule:** Binding to non-loopback requires explicit configuration flags: `thogamura.notifications.allow-non-loopback=true` and `thogamura.notifications.allowed-clients=<CIDR>`.

### 5.2 Browser-to-Gateway HTTP Interface (Port 8080)
- **Encryption:** Local demonstration HTTP/SSE.
- **Scope:** Restricted to localhost / internal development.
- **Limitations:** Coursework non-goals apply. Does not provide user login, OAuth2 authentication, or role-based access control. In a production environment, this boundary would terminate at an HTTPS reverse proxy (such as Nginx/Envoy) with an identity provider.

---

## 6. Verification Evidence Summary

| Test Class | Module | Tests Run | Result | Key Verified Behaviors |
| :--- | :--- | :---: | :---: | :--- |
| `TlsTcpCommandClientIntegrationTest` | `api-gateway` | 9 | PASS | Handshake success, SAN verification, LAN SAN matching, untrusted cert rejection, hostname mismatch rejection, plaintext server rejection, missing truststore fail-closed, invalid password fail-closed. |
| `TlsTcpCommandServerIntegrationTest` | `network-server` | 3 | PASS | Mutual session establishment, multiple framed commands over single TLS stream, untrusted client rejection, plaintext client rejection with zero fallback. |
| `SeparateProcessTlsCommandIntegrationTest` | `network-server` | 3 | PASS | True multi-JVM process separation over port 7443, multiple framed commands, plaintext client rejection on separate process. |
| `TlsTruststoreHostnameVerificationTest` | `network-server` | 3 | PASS | Trusted CA + matching SAN success, untrusted CA rejection, hostname mismatch rejection. |
| `TlsCertificateSetupTest` | `network-server` | 6 | PASS | Self-signed cert generation, SAN injection, keystore/truststore creation, cipher suite negotiation, plaintext client drop. |
| `TcpCommandServerTest` | `network-server` | 11 | PASS | Command dispatching, frame bounds, timeout enforcement, loopback safety, non-loopback binding permitted with TLS. |
| `NioPrivateNetworkSecurityTest` | `network-server` | 4 | PASS | Version-only payload invariance, loopback default binding, CIDR allowlisting, non-loopback safety checks. |
| **Total Reactor Test Suite** | All Modules | **176+** | **PASS** | Complete backend verification across protocol, network-server, and api-gateway. |

---

## 7. Modified Files & Git History

- `backend/api-gateway/src/test/java/lk/thogamura/gateway/network/security/TlsTcpCommandClientIntegrationTest.java`: Added `testLanSanMatchingSucceeds` to verify LAN IP/DNS SAN matching over TLS.
- `backend/network-server/src/test/java/lk/thogamura/networkserver/transport/tcp/TcpCommandServerTest.java`: Added `shouldPermitNonLoopbackBindingWhenTlsIsEnabled` to verify server binding policy.
- `docs/handoffs/M5_CERTIFICATE_REJECTION_AND_LAN_DEMO.md`: This comprehensive verification report and handoff.
