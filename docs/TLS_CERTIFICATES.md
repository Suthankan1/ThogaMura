# Local Development TLS Certificate and Truststore Workflow

- **Document Version:** 1.0.0
- **Track / Phase:** Member 5 (JSSE TLS Networking) / Week 1
- **Lead Developer:** Vibhath Kalsara (@vibhathkalsara99)
- **Reference Issue:** [#5 — [M5][W1] Define local TLS certificate and truststore workflow](https://github.com/Suthankan1/ThogaMura/issues/5)
- **Governing ADRs:** [ADR-004 (TLS Identity Policy)](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/decisions/DECISION_LOG.md#adr-004--tls-identity-policy-accepted), [ADR-006 (Topology & Transport)](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/decisions/DECISION_LOG.md#adr-006--remove-udp-discovery-and-define-practical-network-features-2026-11-17-accepted-as-v2-design), [ADR-007 (Contracts Freeze)](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/decisions/DECISION_LOG.md#adr-007--shared-network-protocol-v1-http-api-v1-and-security-contracts-freeze-2026-11-18-accepted)
- **Status:** **APPROVED BASELINE**

---

## 1. Overview & Security Architecture

The ThogaMura network architecture separates web clients from central warehouse storage using a two-tier boundary:

```
[ Next.js Browser ] 
        │ (HTTP / SSE on :3000 / :8080)
        ▼
[ Spring Boot API Gateway (Branch Laptop) ]
        │ 
        │ (Server-Authenticated Java JSSE TLS / TCP on Port 7443)
        ▼
[ Java Network Server (Warehouse Laptop) ]
        │
        ▼
[ PostgreSQL 17 Database ]
```

### Threat Model & Boundaries
1. **Command Transport (Port 7443):** Carries authoritative stock queries (`GET_PRODUCTS`, `GET_REQUESTS`) and state-mutating requests (`REQUEST_STOCK`, `MARK_PICKED`, `MARK_DISPATCHED`). Because command traffic traverses a shared local area network (LAN) between branch and warehouse machines, it is vulnerable to packet sniffing, unauthorized payload manipulation, and rogue server spoofing.
2. **Mandatory Server-Authenticated TLS:** Under **ADR-004**, all traffic on port 7443 must use Java Secure Socket Extension (JSSE) with server-authenticated TLS (TLS 1.3 / TLS 1.2). The Branch Gateway verifies:
   - The warehouse certificate trust chain terminates at a trusted local demonstration Root Certificate Authority (CA).
   - The certificate is currently valid (not expired or before its validity window).
   - The destination hostname/IP matches an entry in the certificate's **Subject Alternative Name (SAN)** extension.
3. **Mutual TLS (mTLS) Position:** As recorded in ADR-004 and the frozen protocol contract, server-authenticated TLS is the required MVP baseline. Mutual TLS (client certificates verified by the warehouse server) is supported as an optional demonstration extension for advanced multi-machine setups, but is not required for MVP baseline grading.
4. **Strictly Prohibited Shortcuts:**
   - **No Plaintext Fallback:** The server socket on port 7443 rejects any non-TLS connection. Plaintext socket fallback is strictly forbidden.
   - **No Trust-All Managers:** Custom `X509TrustManager` implementations that accept all certificates without validation (e.g., empty `checkServerTrusted()` methods) are strictly banned from the codebase.
   - **No Secrets in Git:** Keystores (`.p12`, `.jks`), private keys (`.key`), certificates (`.crt`), and credential `.env` files must remain strictly outside Git tracking.

---

## 2. Synthetic Demonstration Identities

To ensure zero leakage of real organization or personal information, all local certificates use synthetic coursework identities:

| Entity | Role | Distinguished Name (DN) / Identity |
| :--- | :--- | :--- |
| **Development Root CA** | Trust Anchor | `CN=ThogaMura Dev Root CA, OU=Security, O=ThogaMura Coursework, L=Colombo, ST=Western, C=LK` |
| **Warehouse Server** | Server Identity | `CN=localhost, OU=Warehouse, O=ThogaMura Coursework, L=Colombo, ST=Western, C=LK` |
| **Rogue / Untrusted CA** | Negative Test Attacker | `CN=Rogue Untrusted CA, OU=Attacker, O=Untrusted Entities Inc, L=Nowhere, C=XX` |

---

## 3. Storage Format: PKCS#12 Standard

All keystores and truststores in ThogaMura use the industry-standard **PKCS#12 (`.p12`)** format:
- **Default in Java 9+:** PKCS#12 is the modern default keystore type in JDK 21+ (`KeyStore.getDefaultType()` returns `PKCS12`).
- **Interoperability:** Unlike legacy Sun proprietary JKS (`.jks`), PKCS#12 is supported natively across OpenSSL, curl, browsers, and cross-language toolchains.
- **Strong Encryption:** Standard AES-256 and SHA-256 password-based encryption protect private key entries within `.p12` archives.

---

## 4. Subject Alternative Names (SAN) Requirement

> [!IMPORTANT]
> Modern TLS implementations (JSSE with RFC 2818 / RFC 6125 endpoint identification) **completely ignore the Common Name (`CN`)** for identity matching and **strictly require Subject Alternative Names (SAN)**. A certificate without a matching SAN extension will cause `SSLHandshakeException: No subject alternative DNS name matching <host> found`.

### Required SAN Entries:
- **Localhost Development:**
  ```text
  SAN = dns:localhost, ip:127.0.0.1
  ```
- **Two-Laptop Demonstration (Separate Warehouse Machine):**
  When running the warehouse server on a dedicated laptop, the server certificate **must** include that laptop's LAN IP address or local mDNS hostname:
  ```text
  SAN = dns:localhost, ip:127.0.0.1, ip:192.168.8.100, dns:warehouse.local
  ```

---

## 5. Automated Generation Script

A helper script is provided in the repository to automate generation using standard JDK `keytool`:

```bash
# Standard local development (SAN: dns:localhost, ip:127.0.0.1):
./scripts/generate-dev-certs.sh

# Two-laptop demonstration with warehouse laptop on 192.168.8.100:
./scripts/generate-dev-certs.sh --lan-ip 192.168.8.100

# Additional custom domain names / IPs:
./scripts/generate-dev-certs.sh --san "dns:warehouse.thogamura.internal,ip:10.0.0.5"

# Clean up all generated materials:
./scripts/generate-dev-certs.sh --clean
```

The script generates all materials in the `.certs/` directory (ignored by Git) and prints the exact environment variables to configure in your local `.env`.

---

## 6. Step-by-Step Manual Generation Procedure (Java `keytool`)

For full transparency, the exact `keytool` commands executed by the workflow are documented below.

First, set environment variables with your chosen local passwords (never hardcode passwords into scripts or version control):

```bash
mkdir -p .certs
export KEYSTORE_PASSWORD="<choose-your-local-keystore-password>"
export TRUSTSTORE_PASSWORD="<choose-your-local-truststore-password>"
```

### Step 1: Create the Local Development Root CA
Generate a 2048-bit RSA key pair and self-signed X.509 v3 certificate for the Root CA:

```bash
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
```

### Step 2: Export the Root CA Public Certificate
Export the Root CA certificate in RFC 1421 PEM format so it can be distributed to truststores:

```bash
keytool -exportcert \
  -alias thogamura-ca \
  -keystore .certs/ca-keystore.p12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -rfc \
  -file .certs/ca.crt
```

### Step 3: Generate the Warehouse Server Keypair
Generate a private key and initial self-signed certificate for the warehouse server:

```bash
keytool -genkeypair \
  -alias thogamura-warehouse \
  -keyalg RSA \
  -keysize 2048 \
  -validity 90 \
  -keystore .certs/server-keystore.p12 \
  -storetype PKCS12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -dname "CN=localhost, OU=Warehouse, O=ThogaMura Coursework, L=Colombo, ST=Western, C=LK"
```

### Step 4: Create Certificate Signing Request (CSR)
Generate a CSR from the warehouse server private key:

```bash
keytool -certreq \
  -alias thogamura-warehouse \
  -keystore .certs/server-keystore.p12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -file .certs/server.csr
```

### Step 5: Sign the Server Certificate with the Development CA
Issue the server certificate signed by the Root CA, applying the required SAN extensions and key usages:

```bash
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
```
*(Add `,ip:<LAN_IP>` to the `-ext SAN=...` parameter if targeting a second physical laptop).*

### Step 6: Import CA Certificate into Server Keystore
Import the CA certificate into `server-keystore.p12` to establish the root of the certificate trust chain:

```bash
keytool -importcert \
  -alias thogamura-ca \
  -keystore .certs/server-keystore.p12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -noprompt \
  -file .certs/ca.crt
```

### Step 7: Install Signed Certificate into Server Keystore
Install the CA-signed server certificate back into the keystore, replacing the temporary self-signed certificate and completing the certificate chain:

```bash
keytool -importcert \
  -alias thogamura-warehouse \
  -keystore .certs/server-keystore.p12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -file .certs/server.crt
```

### Step 8: Create the Branch Gateway Truststore
Create `gateway-truststore.p12` containing **only** the trusted Root CA certificate:

```bash
keytool -importcert \
  -alias thogamura-ca \
  -keystore .certs/gateway-truststore.p12 \
  -storetype PKCS12 \
  -storepass "$TRUSTSTORE_PASSWORD" \
  -noprompt \
  -file .certs/ca.crt
```

> [!CAUTION]
> **Truststore Principle:** The Branch Gateway truststore must **never** contain private keys (`server-keystore.p12` or `ca-keystore.p12`). A truststore contains solely public X.509 certificates of approved Certificate Authorities.

### Step 9: Restrict File Permissions
Ensure generated private keys and keystores cannot be read by other local users:

```bash
chmod 600 .certs/*.p12
chmod 644 .certs/*.crt
rm -f .certs/server.csr
```

---

## 7. Alternative OpenSSL Generation Workflow

For developers who prefer standard OpenSSL command-line tools:

```bash
mkdir -p .certs
export KEYSTORE_PASSWORD="<choose-your-local-keystore-password>"
export TRUSTSTORE_PASSWORD="<choose-your-local-truststore-password>"

# 1. Create Root CA key and certificate
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -keyout .certs/ca.key -out .certs/ca.crt \
  -subj "/C=LK/ST=Western/L=Colombo/O=ThogaMura Coursework/OU=Security/CN=ThogaMura Dev Root CA" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"

# 2. Generate Server key and CSR
openssl req -newkey rsa:2048 -nodes \
  -keyout .certs/server.key -out .certs/server.csr \
  -subj "/C=LK/ST=Western/L=Colombo/O=ThogaMura Coursework/OU=Warehouse/CN=localhost"

# 3. Create extension config file for SAN
cat <<EOF > .certs/server_ext.cnf
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=@alt_names

[alt_names]
DNS.1 = localhost
IP.1 = 127.0.0.1
EOF

# 4. Sign server certificate with Root CA
openssl x509 -req -in .certs/server.csr -CA .certs/ca.crt -CAkey .certs/ca.key -CAcreateserial \
  -out .certs/server.crt -days 90 -extfile .certs/server_ext.cnf

# 5. Package server certificate chain and private key into PKCS#12
openssl pkcs12 -export \
  -in .certs/server.crt \
  -inkey .certs/server.key \
  -certfile .certs/ca.crt \
  -out .certs/server-keystore.p12 \
  -name thogamura-warehouse \
  -password pass:"$KEYSTORE_PASSWORD"

# 6. Import Root CA into Gateway PKCS#12 Truststore using keytool
keytool -importcert -alias thogamura-ca -file .certs/ca.crt \
  -keystore .certs/gateway-truststore.p12 -storetype PKCS12 \
  -storepass "$TRUSTSTORE_PASSWORD" -noprompt

# 7. Secure permissions
chmod 600 .certs/*.p12 .certs/*.key
chmod 644 .certs/*.crt
rm -f .certs/server.csr .certs/server_ext.cnf .certs/*.srl
```

---

## 8. Environment Variable Configuration

Both the `network-server` and `api-gateway` JVM processes consume certificate and trust configuration via environment variables:

### Central Warehouse Network Server (`backend/network-server`):
Configured on the machine hosting the Java socket server:

| Variable Name | Default Value | Description |
| :--- | :--- | :--- |
| `THOGAMURA_COMMAND_PORT` | `7443` | Port for the TLS-encrypted TCP command listener. |
| `THOGAMURA_TLS_KEYSTORE_PATH` | `.certs/server-keystore.p12` | Absolute or relative filesystem path to the server keystore. |
| `THOGAMURA_TLS_KEYSTORE_PASSWORD` | *(required)* | Password protecting the server keystore. |
| `THOGAMURA_TLS_KEYSTORE_TYPE` | `PKCS12` | Keystore format (`PKCS12` or `JKS`). |

### Branch Gateway (`backend/api-gateway`):
Configured on the machine hosting the Spring Boot API gateway:

| Variable Name | Default Value | Description |
| :--- | :--- | :--- |
| `THOGAMURA_SERVER_HOST` | `localhost` | Configured IP address or hostname of the warehouse server. |
| `THOGAMURA_COMMAND_PORT` | `7443` | Port of the central warehouse server. |
| `THOGAMURA_TLS_TRUSTSTORE_PATH` | `.certs/gateway-truststore.p12` | Absolute or relative filesystem path to the gateway truststore. |
| `THOGAMURA_TLS_TRUSTSTORE_PASSWORD` | *(required)* | Password protecting the gateway truststore. |
| `THOGAMURA_TLS_TRUSTSTORE_TYPE` | `PKCS12` | Truststore format (`PKCS12` or `JKS`). |
| `THOGAMURA_TLS_HOSTNAME_VERIFICATION` | `true` | Enforce RFC 2818 endpoint identification (must **always** be `true`). |

---

## 9. Hostname Verification & Fail-Closed Behavior

Java JSSE performs hostname verification using endpoint identification algorithms. During the TLS handshake, the client checks:

```java
SSLParameters sslParameters = sslSocket.getSSLParameters();
sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
sslSocket.setSSLParameters(sslParameters);
```

### Verification Rules:
1. **Host Matching:** The host passed to `new Socket(host, port)` (e.g., `127.0.0.1` or `192.168.8.100`) must exactly match a corresponding `IPAddress` or `DNSName` entry in the server certificate's SAN.
2. **Untrusted CA Rejection:** If the server certificate is signed by an unrecognized CA or is self-signed without being present in `gateway-truststore.p12`, JSSE immediately aborts the connection with:
   ```text
   javax.net.ssl.SSLHandshakeException: PKIX path building failed: 
   sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
   ```
3. **Mismatched Hostname Rejection:** If the warehouse certificate SAN specifies `localhost` and `127.0.0.1`, but the gateway connects to `wrong-host.thogamura.internal` or an unlisted IP, JSSE immediately aborts with:
   ```text
   javax.net.ssl.SSLHandshakeException: No subject alternative DNS name matching wrong-host.thogamura.internal found
   ```
4. **Plaintext Connection Rejection:** If a non-TLS client attempts to connect to port 7443, the server JSSE layer detects invalid TLS record framing and rejects the socket. No plaintext fallback occurs.

---

## 10. Certificate Renewal & LAN IP Recreation

When moving between networks (e.g., changing Wi-Fi access points or transitioning from loopback to a two-laptop demo LAN), the server certificate must be re-issued with the new LAN IP:

1. Identify the warehouse laptop's LAN IP address:
   - **macOS:** `ipconfig getifaddr en0` (or `en1` for Wi-Fi)
   - **Linux:** `hostname -I | awk '{print $1}'`
   - **Windows:** `ipconfig` (IPv4 Address)
2. Re-run the generator with the new IP address:
   ```bash
   ./scripts/generate-dev-certs.sh --lan-ip 192.168.1.150
   ```
3. Distribute **only** `gateway-truststore.p12` (or `ca.crt`) to the branch gateway laptop. Since the Root CA remains valid, the gateway truststore does not require recreation if `ca-keystore.p12` was preserved!

---

## 11. Cleanup Procedure

To remove all generated certificate files before a clean test or repository packaging:

```bash
# Automated cleanup:
./scripts/generate-dev-certs.sh --clean

# Or manual removal:
rm -rf .certs/
```

Verify that Git remains clean:
```bash
git status
git diff --check
```
The repository `.gitignore` explicitly prevents tracking of `.certs/`, `certs/`, `*.p12`, `*.jks`, `*.key`, `*.pem`, `*.crt`, and `*.csr`.

---

## 12. Next Implementation Step

This document and associated tooling complete **Issue #5 (Certificate and Truststore Workflow)**.

The next task on the Member 5 roadmap is **Issue #23 — Validated Java TLS Command Transport**:
- Implementing `TlsSocketFactory` in `lk.thogamura.gateway.network.security`.
- Implementing `TlsServerSocketFactory` in `lk.thogamura.networkserver.security`.
- Wrapping Member 1's TCP framed command listener (`Issue #7`) in validated JSSE TLS on port 7443.
