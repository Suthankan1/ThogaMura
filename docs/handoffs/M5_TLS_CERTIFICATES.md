# Member 5 Handoff — TLS Certificate and Truststore Workflow (Issue #5)

- **Owner:** Member 5 (@vibhathkalsara99 — Vibhath Kalsara)
- **Role:** Java TLS Networking (JSSE, Certificates, Truststores, and Hostname Verification)
- **Module:** `backend/network-server` (`lk.thogamura.networkserver.security`), `backend/api-gateway` (`lk.thogamura.gateway.network.security`)
- **Status:** Complete (Verified with JDK 21+ JSSE and standard Java `keytool`)
- **Reference Issue:** [#5 — [M5][W1] Define local TLS certificate and truststore workflow](https://github.com/Suthankan1/ThogaMura/issues/5)
- **Pull Request:** [PR #47 — docs(m5): establish TLS certificate and truststore workflow](https://github.com/Suthankan1/ThogaMura/pull/47)
- **Governing Contracts:** [ADR-004 (TLS Identity Policy)](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/decisions/DECISION_LOG.md#adr-004--tls-identity-policy-accepted), [NETWORK_PROTOCOL_V1.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/contracts/NETWORK_PROTOCOL_V1.md#8-tls--transport-security-specification-port-7443), [SECURITY_AND_LIMITATIONS.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/SECURITY_AND_LIMITATIONS.md)

---

## 1. Exact Setup Commands

All certificates and keystores are generated using standard JDK `keytool` in industry-standard PKCS#12 format.

### Option A: Automated Developer Script (Recommended)
```bash
# Standard local development (SAN: dns:localhost, ip:127.0.0.1):
./scripts/generate-dev-certs.sh

# Two-laptop demonstration with warehouse laptop on 192.168.8.100:
./scripts/generate-dev-certs.sh --lan-ip 192.168.8.100

# Custom domain names / IP addresses:
./scripts/generate-dev-certs.sh --san "dns:warehouse.thogamura.internal,ip:10.0.0.5"

# Clean up local generated materials:
./scripts/generate-dev-certs.sh --clean
```

### Option B: Step-by-Step Manual Generation Commands
```bash
mkdir -p .certs
export KEYSTORE_PASSWORD="<choose-your-local-keystore-password>"
export TRUSTSTORE_PASSWORD="<choose-your-local-truststore-password>"

# 1. Create Development Root CA (365 days validity)
keytool -genkeypair \
  -alias thogamura-ca \
  -keyalg RSA \
  -keysize 2048 \
  -validity 365 \
  -keystore .certs/ca-keystore.p12 \
  -storetype PKCS12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -dname "CN=ThogaMura Dev Root CA, OU=Security, O=ThogaMura Coursework, L=Colombo, ST=Western, C=LK" \
  -ext BasicConstraints=ca:true,pathlen:0 \
  -ext KeyUsage=keyCertSign,cRLSign

# 2. Export Root CA Public Certificate
keytool -exportcert \
  -alias thogamura-ca \
  -keystore .certs/ca-keystore.p12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -rfc \
  -file .certs/ca.crt

# 3. Create Warehouse Server Keypair (90 days validity)
keytool -genkeypair \
  -alias thogamura-warehouse \
  -keyalg RSA \
  -keysize 2048 \
  -validity 90 \
  -keystore .certs/server-keystore.p12 \
  -storetype PKCS12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -dname "CN=localhost, OU=Warehouse, O=ThogaMura Coursework, L=Colombo, ST=Western, C=LK"

# 4. Generate Certificate Signing Request (CSR)
keytool -certreq \
  -alias thogamura-warehouse \
  -keystore .certs/server-keystore.p12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -file .certs/server.csr

# 5. CA signs Server CSR with Subject Alternative Names (SAN)
keytool -gencert \
  -alias thogamura-ca \
  -keystore .certs/ca-keystore.p12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -infile .certs/server.csr \
  -outfile .certs/server.crt \
  -rfc \
  -validity 90 \
  -ext BasicConstraints=ca:false \
  -ext KeyUsage=digitalSignature,keyEncipherment \
  -ext ExtendedKeyUsage=serverAuth \
  -ext SAN=dns:localhost,ip:127.0.0.1

# 6. Import CA Certificate into Server Keystore (chain root)
keytool -importcert \
  -alias thogamura-ca \
  -keystore .certs/server-keystore.p12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -noprompt \
  -file .certs/ca.crt

# 7. Install Signed Certificate into Server Keystore
keytool -importcert \
  -alias thogamura-warehouse \
  -keystore .certs/server-keystore.p12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -file .certs/server.crt

# 8. Create Gateway Truststore (Contains CA ONLY, NO private key)
keytool -importcert \
  -alias thogamura-ca \
  -keystore .certs/gateway-truststore.p12 \
  -storetype PKCS12 \
  -storepass "$TRUSTSTORE_PASSWORD" \
  -noprompt \
  -file .certs/ca.crt

# 9. Secure permissions & remove CSR
chmod 600 .certs/*.p12
chmod 644 .certs/*.crt
rm -f .certs/server.csr
```

---

## 2. Validation Results

A total of **9 new automated unit/integration tests** were authored across both modules, with **0 failures, 0 errors**:

### 1. `lk.thogamura.networkserver.security.TlsCertificateSetupTest` (4 tests)
```bash
mvn -f backend/network-server/pom.xml test -Dtest=TlsCertificateSetupTest
```
- `testValidServerKeystoreLoadsAndContainsCompleteChain`: Confirms server keystore loads via `KeyStore.getInstance("PKCS12")`, contains entry `thogamura-warehouse`, has certificate chain length 2, and leaf issuer matches Dev Root CA subject DN.
- `testServerCertificateContainsRequiredSubjectAlternativeNames`: Confirms X.509 certificate has `SubjectAlternativeNames` with type 2 (`dns:localhost`) and type 7 (`ip:127.0.0.1`), key usage `digitalSignature,keyEncipherment`, and extended key usage `serverAuth`.
- `testServerKeystoreRejectsWrongPassword`: Confirms loading keystore with incorrect password throws `IOException` (fails closed).
- `testServerRejectsPlaintextConnectionWithoutFallback`: Confirms that when a standard `Socket` connects and sends framed JSON (`{"v":1,"type":"PING"}`), the `SSLServerSocket` throws an `SSLException` (`Unsupported or unrecognized SSL message`) and terminates the connection without sending unencrypted plaintext or falling back.

### 2. `lk.thogamura.gateway.network.security.TlsTruststoreHostnameVerificationTest` (5 tests)
```bash
mvn -f backend/api-gateway/pom.xml test -Dtest=TlsTruststoreHostnameVerificationTest
```
- `testGatewayTruststoreLoadsAndContainsOnlyTrustedCa`: Confirms gateway truststore contains alias `thogamura-ca` as a `trustedCertEntry` and **strictly zero private keys**.
- `testSuccessfulTlsHandshakeWithTrustedCaAndMatchingSan`: Starts live SSL echo server; client connects with `HTTPS` endpoint identification to `localhost`; handshake succeeds with negotiated `TLSv1.3` / `TLSv1.2` and exchanges framed wire messages.
- `testHandshakeFailsWhenServerCertificateIsSignedByUntrustedCa`: Negative test; server presents certificate signed by rogue/untrusted CA (`CN=Rogue Untrusted CA`); client handshake immediately throws `SSLHandshakeException` (`PKIX path building failed: unable to find valid certification path to requested target`).
- `testHandshakeFailsWhenHostnameDoesNotMatchSan`: Negative test; server presents certificate with SAN `dns:wrong-host.thogamura.internal,ip:192.0.2.1`; client connects to `127.0.0.1`; handshake immediately throws `SSLHandshakeException` (`No subject alternative names matching IP address 127.0.0.1 found`).
- `testNoTrustAllManagerAccepted`: Confirms that missing or invalid truststore path throws `FileNotFoundException` and fails closed without falling back to trust-all strategy.

### Full Reactor Test Pass
```bash
mvn -f backend/pom.xml test
```
- **Shared wire protocol:** 21 tests passed
- **Java network server:** 50 tests passed
- **HTTP/SSE API gateway:** 48 tests passed
- **Total backend suite:** 119 tests run, 0 failures, 0 errors, 0 skipped.

---

## 3. Required SAN and Hostname Configuration

Under RFC 2818 and RFC 6125, Java JSSE endpoint identification algorithm `"HTTPS"` ignores `CN` and validates the destination host against Subject Alternative Names:

```java
SSLParameters params = sslSocket.getSSLParameters();
params.setEndpointIdentificationAlgorithm("HTTPS");
sslSocket.setSSLParameters(params);
```

| Deployment Mode | Connecting Host | Required Certificate SAN Configuration |
| :--- | :--- | :--- |
| **Local Development** | `localhost` or `127.0.0.1` | `SAN=dns:localhost,ip:127.0.0.1` |
| **Two-Laptop Demonstration** | e.g. `192.168.8.100` | `SAN=dns:localhost,ip:127.0.0.1,ip:192.168.8.100` |
| **Local mDNS Hostname** | `warehouse.local` | `SAN=dns:localhost,ip:127.0.0.1,dns:warehouse.local` |

---

## 4. Credential Safety Precautions

1. **Keystore & Truststore Isolation:**
   - Server keystore (`server-keystore.p12`) resides solely on the warehouse machine.
   - Gateway truststore (`gateway-truststore.p12`) contains solely the public CA certificate and **never** private keys.
2. **Repository Exclusion (`.gitignore`):**
   - Explicit exclusion of `.certs/`, `certs/`, `*.p12`, `*.jks`, `*.key`, `*.pem`, `*.crt`, `*.csr`, and `*.srl`.
   - Automated tests generate ephemeral certificates in JUnit `@TempDir` without persisting keys to the repository.
3. **Environment Variable Decoupling:**
   - Credential variables (`THOGAMURA_TLS_KEYSTORE_PASSWORD`, `THOGAMURA_TLS_TRUSTSTORE_PASSWORD`) reside in `.env` (untracked) and are documented as templates in `.env.example`.
4. **Permissions:**
   - Keystores and private keys generated by `generate-dev-certs.sh` are set to `chmod 600`.

---

## 5. Commits and Pull Request

- **Pull Request:** [https://github.com/Suthankan1/ThogaMura/pull/47](https://github.com/Suthankan1/ThogaMura/pull/47)
- **Commits:**
  1. `76bb095` `docs(m5): define TLS certificate and truststore workflow`
  2. `7fa6d53` `test(m5): verify certificate setup assumptions`
  3. *(This handoff)* `docs(m5): record TLS certificate and truststore handoff`

---

## 6. Remaining Limitations & Next Task

- **Issue #5 Boundary:** This issue establishes the certificate generation workflow, PKCS#12 layout, truststore architecture, environment property definitions, and security assumption tests. It does **not** claim full `SSLSocket` command transport integration into runtime application listeners.
- **Mutual TLS (mTLS):** Optional enhancement for advanced demonstration; server-authenticated TLS is the accepted MVP baseline per ADR-004.
- **Next Task:** **Issue #23 — Validated Java TLS Command Transport** (Member 5):
  - Implement `TlsSocketFactory` in `lk.thogamura.gateway.network.security`.
  - Implement `TlsServerSocketFactory` in `lk.thogamura.networkserver.security`.
  - Wrap Member 1's TCP framed command listener (Issue #7) in validated JSSE TLS on port 7443.
