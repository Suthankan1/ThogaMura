# Member 5 Handoff — Secure Java TLS Command Transport (Issue #23)

- **Owner:** Member 5 (@vibhathkalsara99 — Vibhath Kalsara)
- **Role:** Java TLS Networking (JSSE `SSLContext`, `SSLSocket`, `SSLServerSocket`, Keystores, Truststores, and SAN Hostname Verification)
- **Module:** `backend/network-server` (`lk.thogamura.networkserver.security`) and `backend/api-gateway` (`lk.thogamura.gateway.network.security`)
- **Status:** Complete (Verified with automated unit, socket integration, separate-JVM process tests, and fail-closed certificate rejection suites)
- **Reference Issue:** [#23 — [M5][W2] Secure Java TLS Command Transport](https://github.com/Suthankan1/ThogaMura/issues/23)
- **Governing Contracts:** [ADR-004 (TLS Identity Policy)](../decisions/DECISION_LOG.md#adr-004--tls-identity-policy-accepted), [ADR-006 (Topology & Transport)](../decisions/DECISION_LOG.md#adr-006--remove-udp-discovery-and-define-practical-network-features-2026-11-17-accepted-as-v2-design), [ADR-007 (Contracts Freeze)](../decisions/DECISION_LOG.md#adr-007--shared-network-protocol-v1-http-api-v1-and-security-contracts-freeze-2026-11-18-accepted), [NETWORK_PROTOCOL_V1.md](../contracts/NETWORK_PROTOCOL_V1.md#8-tls--transport-security-specification-port-7443), [SECURITY_AND_LIMITATIONS.md](../SECURITY_AND_LIMITATIONS.md), [TLS_CERTIFICATES.md](../TLS_CERTIFICATES.md)

---

## 1. Overview & Security Architecture

This work delivers validated Java Secure Socket Extension (JSSE) TLS transport for all business and diagnostic commands traversing the network boundary between the Branch API Gateway and Central Warehouse Server on port 7443.

```
┌────────────────────────────────────────────────────────┐
│               Branch Gateway (Client JVM)              │
│                                                        │
│  [TcpCommandClient]                                    │
│         │                                              │
│         ▼                                              │
│  [TlsSocketFactory] (SSLSocketFactory)                 │
│         │                                              │
│         ├── Configured with PKCS#12 Truststore         │
│         └── RFC 2818 Endpoint Identification ("HTTPS") │
└──────────────────────────┬─────────────────────────────┘
                           │
                           │  Validated JSSE TLS Handshake (Port 7443)
                           │  - TLSv1.3 / TLSv1.2
                           │  - Bounded newline JSON frames (<= 64 KB)
                           │  - UUID correlation (requestId)
                           │  - NO Plaintext Fallback
                           ▼
┌────────────────────────────────────────────────────────┐
│           Central Warehouse Server (Host JVM)          │
│                                                        │
│  [TlsServerSocketFactory] (SSLServerSocketFactory)     │
│         │                                              │
│         ├── Loaded with PKCS#12 Server Keystore        │
│         └── Leaf Cert + Dev Root CA Trust Chain        │
│         ▼                                              │
│  [TcpCommandServer] (Port 7443)                        │
│         │                                              │
│         ▼                                              │
│  [CommandDispatcher] (PING/PONG, REQUEST_STOCK, etc.)  │
└────────────────────────────────────────────────────────┘
```

---

## 2. Core Java JSSE Networking Concepts

1. **`SSLContext` Initialization:**
   - **Warehouse Server:** Initialized with `KeyManagerFactory` holding the server's private key and certificate chain from `server-keystore.p12`.
   - **Branch Gateway:** Initialized with `TrustManagerFactory` containing solely the public demonstration Root CA certificate from `gateway-truststore.p12` (strictly zero private keys).

2. **Custom `ServerSocketFactory` and `SocketFactory`:**
   - `TlsServerSocketFactory` extends `javax.net.ServerSocketFactory`, creating `SSLServerSocket` instances configured with enabled protocols (`TLSv1.3, TLSv1.2`).
   - `TlsSocketFactory` extends `javax.net.SocketFactory`, creating `SSLSocket` instances and configuring RFC 2818 endpoint identification algorithm (`"HTTPS"`).

3. **RFC 2818 Endpoint Identification & SAN Matching:**
   ```java
   SSLParameters params = sslSocket.getSSLParameters();
   params.setEndpointIdentificationAlgorithm("HTTPS");
   sslSocket.setSSLParameters(params);
   ```
   During the TLS handshake, JSSE compares the requested connection host/IP against the Subject Alternative Names (SAN) extension in the server certificate. Any mismatch immediately aborts the connection with `SSLHandshakeException: No subject alternative names matching...`.

4. **Zero Plaintext Fallback (Fail-Closed):**
   - The warehouse listener on port 7443 exclusively accepts TLS connections.
   - When a plaintext client connects and sends raw bytes (e.g. `{"v":1...}`), the JSSE record layer detects the missing TLS record header and throws `SSLException: Unsupported or unrecognized SSL message`.
   - The server immediately drops the connection without returning any unencrypted plaintext response.

5. **Protocol & Wire Invariance:**
   - Framing bounds (64 KB `\n` delimiter via `TcpFrameReader` / `TcpFrameWriter`) and envelope schemas (`v: 1`, `requestId`) remain completely unchanged.
   - TLS encrypts the underlying TCP byte stream beneath the application framing codec.

---

## 3. Configuration Properties

### Warehouse Network Server (`backend/network-server`):
```yaml
thogamura:
  command:
    server:
      enabled: ${THOGAMURA_COMMAND_SERVER_ENABLED:true}
      host: ${THOGAMURA_COMMAND_SERVER_HOST:127.0.0.1}
      port: ${THOGAMURA_COMMAND_SERVER_PORT:7443}
  tls:
    server:
      enabled: ${THOGAMURA_TLS_SERVER_ENABLED:${THOGAMURA_TLS_ENABLED:true}}
      keystore-path: ${THOGAMURA_TLS_KEYSTORE_PATH:.certs/server-keystore.p12}
      keystore-password: ${THOGAMURA_TLS_KEYSTORE_PASSWORD}
      keystore-type: ${THOGAMURA_TLS_KEYSTORE_TYPE:PKCS12}
      protocols: ${THOGAMURA_TLS_PROTOCOLS:TLSv1.3,TLSv1.2}
```

### Branch API Gateway (`backend/api-gateway`):
```yaml
thogamura:
  command:
    client:
      host: ${THOGAMURA_WAREHOUSE_HOST:${THOGAMURA_COMMAND_HOST:127.0.0.1}}
      port: ${THOGAMURA_COMMAND_PORT:7443}
  tls:
    client:
      enabled: ${THOGAMURA_TLS_CLIENT_ENABLED:${THOGAMURA_TLS_ENABLED:true}}
      truststore-path: ${THOGAMURA_TLS_TRUSTSTORE_PATH:.certs/gateway-truststore.p12}
      truststore-password: ${THOGAMURA_TLS_TRUSTSTORE_PASSWORD}
      truststore-type: ${THOGAMURA_TLS_TRUSTSTORE_TYPE:PKCS12}
      hostname-verification: ${THOGAMURA_TLS_HOSTNAME_VERIFICATION:true}
      protocols: ${THOGAMURA_TLS_PROTOCOLS:TLSv1.3,TLSv1.2}
```

---

## 4. Class Layout & Files Created/Modified

### `backend/network-server`:
- `lk.thogamura.networkserver.security.TlsServerProperties`: Type-safe configuration binder for server TLS properties.
- `lk.thogamura.networkserver.security.TlsServerSocketFactory`: JSSE `ServerSocketFactory` wrapper enforcing PKCS#12 keystore loading, protocol negotiation, and fail-closed initialization.
- `lk.thogamura.networkserver.security.TlsServerSecurityConfiguration`: Spring configuration producing `ServerSocketFactory` bean when `thogamura.tls.server.enabled=true`.
- `lk.thogamura.networkserver.transport.tcp.TcpCommandServerConfig`: Enhanced with `tlsEnabled` flag, permitting non-loopback binding when TLS is active.
- `lk.thogamura.networkserver.transport.tcp.TcpCommandServer`: Added TLS handshake execution, plaintext client rejection, and CLI argument support for keystore/password in `main()`.
- `lk.thogamura.networkserver.transport.tcp.TcpCommandServerLifecycle`: Injects configured `ServerSocketFactory` bean into server lifecycle.

### `backend/api-gateway`:
- `lk.thogamura.gateway.network.security.TlsClientProperties`: Type-safe configuration binder for gateway TLS properties.
- `lk.thogamura.gateway.network.security.TlsSocketFactory`: JSSE `SocketFactory` enforcing PKCS#12 truststore validation and RFC 2818 endpoint identification (`"HTTPS"`).
- `lk.thogamura.gateway.network.security.TlsClientSecurityConfiguration`: Spring configuration producing `SocketFactory` bean when `thogamura.tls.client.enabled=true`.
- `lk.thogamura.gateway.network.tcp.TcpCommandClient`: Performs explicit TLS handshake check on `SSLSocket`, translating `SSLException` into descriptive `CommandTransportException`.
- `lk.thogamura.gateway.network.tcp.TcpCommandClientConfiguration`: Injects configured `SocketFactory` bean into `TcpCommandClient`.

---

## 5. Verification & Test Evidence

All 176 backend tests passed across the reactor build (`protocol`: 21, `network-server`: 84, `api-gateway`: 71).

### Security Scenarios Verified:
1. **Successful TLS Handshake & Exchange (`TlsTcpCommandServerIntegrationTest`, `TlsTcpCommandClientIntegrationTest`):**
   - Negotiates TLSv1.3 / TLSv1.2.
   - PING/PONG and stateful framed business commands succeed with validated certificate chain.
2. **Subject Alternative Name (SAN) Enforcement (`TlsTruststoreHostnameVerificationTest`, `TlsTcpCommandClientIntegrationTest`):**
   - Connecting to an unlisted IP or hostname fails closed with `SSLHandshakeException: No subject alternative names matching...`.
3. **Untrusted CA Rejection (`TlsTruststoreHostnameVerificationTest`, `TlsTcpCommandClientIntegrationTest`):**
   - Rogue or attacker CA certificates fail closed with `SSLHandshakeException: PKIX path building failed`.
4. **Zero Plaintext Fallback (`TlsCertificateSetupTest`, `TlsTcpCommandServerIntegrationTest`, `SeparateProcessTlsCommandIntegrationTest`):**
   - Plaintext clients connecting to the TLS port receive NO unencrypted JSON frames; connections are dropped immediately with TLS alert record `0x15`.
5. **Separate JVM Multi-Process Execution (`SeparateProcessTlsCommandIntegrationTest`):**
   - Proves true operating system process boundary: child JVM server process (distinct PID) securely serves commands to parent JVM client process over validated JSSE TLS sockets.
6. **Missing / Invalid Credentials Fail-Closed (`TlsCertificateSetupTest`, `TlsTcpCommandClientIntegrationTest`):**
   - Missing keystore/truststore files throw `FileNotFoundException`.
   - Wrong passwords throw `IOException`.
   - Zero "trust-all" bypasses permitted.

---

## 6. Remaining Security Limitations & Scope Boundary

1. **Server-Authenticated TLS Baseline:**
   Under ADR-004 and the frozen protocol contract, server-authenticated TLS is the required coursework baseline. Mutual TLS (client certificates verified by the warehouse) is supported as an optional demonstration extension for multi-machine setups.
2. **NIO Private LAN Notification Feed:**
   The private Java NIO notification feed on port 7444 remains unencrypted by design (ADR-006). It carries only opaque numeric cache invalidation signals (`version: 101`), contains strictly NO customer or stock information, and must remain restricted to a demo private LAN.

---

## 7. Next Implementation Step

- **Next Issue:** [#24 — [M5][W2] Demonstrate certificate rejection and secure LAN operation](https://github.com/Suthankan1/ThogaMura/issues/24)
- **Integration with Member 4:** Transactional command execution (`REQUEST_STOCK`, `MARK_PICKED`, `MARK_DISPATCHED`) runs seamlessly over validated JSSE TLS without transport modification.

## Stabilization handoff

The implemented command policy is server-authenticated JSSE TLS with SAN verification. Non-loopback command listeners require TLS. The physical LAN rehearsal must still prove peer firewall isolation and wrong identity rejection.

## Workspace access handoff

Review the gateway's session and assertion path and the warehouse verification boundary. Keep role, branch, clock and key configuration synchronized. The branch still verifies the warehouse SAN in JSSE. The application signs over a trusted gateway perimeter. [Workspace setup](../SETUP.md#authenticated-branch-workspace)
