# Handoff — Member 3 (Suthankan Balenthiran) / Private LAN Security for Java NIO (Issue #33)

- **Developer & Role:** Suthankan Balenthiran (@Suthankan1) — Member 3 (M3): Java NIO and Real-Time Notifications.
- **Collaborator:** Vibhath Kalsara (@vibhathkalsara99) — Member 5: TLS & Transport Security.
- **Branch / PR:** Branch `feat/m3-private-nio-network-33`, targeting `main`. Reference: `Refs #33`.
- **Target Issue:** [Issue #33: [SHARED][W4] Restrict the NIO change feed to the private demo network](https://github.com/Suthankan1/ThogaMura/issues/33).
- **Core Purpose:** Hardens the non-blocking Java NIO notification server against accidental public network exposure by enforcing loopback defaults, prohibiting wildcard binds (`0.0.0.0`), requiring explicit operator authorization for non-loopback interfaces, implementing application-level client IP allowlisting, and defining host firewall deployment procedures for the university demonstration.

---

## 1. Threat Model & Security Problem

The central warehouse publishes change notifications to branch gateways over a custom Java NIO TCP socket feed (port 7444).
```json
{"v":1,"type":"CHANGED","version":42}
```

### Risk Assessment
1. **Unencrypted Plaintext Feed:** In this coursework MVP, the NIO notification feed is unencrypted to demonstrate pure JDK non-blocking network programming (`ServerSocketChannel`, `SocketChannel`, `Selector`, `SelectionKey`, `ByteBuffer`).
2. **Exposure Risks:** If exposed to an untrusted or public network (e.g. campus Wi-Fi, public internet):
   - Eavesdroppers could observe cache invalidation timings and version increments.
   - Malicious actors could mount denial-of-service (DoS) attacks by holding sockets open or flooding connection slots.
   - Rogue clients could connect and consume server resources.

### Architectural Defense in Depth
- **Zero Business Data in Payload:** The feed transmits *strictly* version numbers (`v`, `type`, `version`). Under no circumstances are SKUs, product quantities, customer records, operator IDs, or credentials transmitted over NIO.
- **Strict Bind Interface Control:** Wildcard binding (`0.0.0.0` / `::`) is **prohibited**. The server binds to `127.0.0.1` by default and requires explicit operator confirmation before binding to a private LAN IP.
- **Application-Level Source Filtering:** Non-approved client IPs are denied immediately upon `accept()` and their channels closed without registering with the selector.
- **Host Firewall Enforcement:** The warehouse host firewall restricts inbound TCP port 7444 strictly to approved branch gateway laptops on the private demo LAN.

---

## 2. Configuration & Network Controls

### A. Safe Defaults (Local Development)
By default, the server binds strictly to the loopback interface:
- **Host:** `127.0.0.1`
- **Port:** `7444`
- **Allow Non-Loopback:** `false`
- **Allowed Clients:** Loopback only (`127.0.0.1`, `localhost`, `::1`)

### B. Explicit Private LAN Configuration (Demo Network)
When deployed across two physical machines for the demonstration:
- **Warehouse Laptop:** e.g., `192.168.1.10`
- **Branch Gateway Laptop:** e.g., `192.168.1.20`

To expose the listener beyond loopback, the operator must provide **explicit approval**:
```yaml
thogamura:
  notifications:
    enabled: true
    host: 192.168.1.10
    port: 7444
    allow-non-loopback: true
    allowed-clients: 192.168.1.20
```

Environment variable overrides:
```bash
export THOGAMURA_NOTIFICATIONS_ENABLED=true
export THOGAMURA_NOTIFICATIONS_HOST=192.168.1.10
export THOGAMURA_NOTIFICATIONS_PORT=7444
export THOGAMURA_NOTIFICATIONS_ALLOW_NON_LOOPBACK=true
export THOGAMURA_NOTIFICATIONS_ALLOWED_CLIENTS=192.168.1.20
```

### C. Configuration Validation & Fail-Closed Behavior
1. **Wildcard Rejection:** Attempting to configure `host: 0.0.0.0` or `host: ::` throws `IllegalArgumentException`. Wildcard binding is strictly prohibited.
2. **Missing Operator Approval:** Attempting to bind to `192.168.1.10` with `allow-non-loopback=false` throws `IllegalArgumentException`.
3. **Wildcard Client Rejection:** Setting `allowed-clients: *` or `allowed-clients: 0.0.0.0` throws `IllegalArgumentException`.
4. **Startup Audit Logging:** Logs bind address, mode, and allowed client count without leaking secrets:
   ```text
   Java NIO notification server started on 192.168.1.10:7444 [bindMode=PRIVATE_LAN, allowedClients=[192.168.1.20], maxClients=100, maxPendingQueue=50]
   ```

---

## 3. Application-Level Connection Filtering

When a client initiates a TCP handshake:
1. `ServerSocketChannel.accept()` receives the non-blocking client `SocketChannel`.
2. Remote IP is extracted via `clientChannel.getRemoteAddress()`.
3. `config.isClientAllowed(remoteAddress)` validates the remote IP:
   - If permitted: Configures channel non-blocking, registers `OP_READ` with selector, creates `ClientSession`.
   - If **unauthorized**:
     - Increments `rejectedConnections` metric counter.
     - Logs warning: `Rejected unauthorized NIO notification connection from /192.168.1.55:54321`.
     - **Immediately closes channel (`clientChannel.close()`)**.
     - The channel is never registered with the selector; no `ClientSession` or `ByteBuffer` memory is allocated.

---

## 4. Host Firewall Deployment Guide for University Demonstration

> [!IMPORTANT]
> **Safety Boundary:** Operating system firewall rules must **never** be applied automatically by scripts or background tasks. Machine-wide firewall modifications require explicit authorization by the laptop owner.

### How to Identify the Warehouse Private LAN IP
- **macOS:** `ipconfig getifaddr en0` (Wi-Fi) or `ipconfig getifaddr en5` (Ethernet)
- **Linux:** `ip -4 addr show scope global | grep inet`
- **Windows:** `ipconfig | findstr /i "IPv4"`

### A. macOS (`pfctl` / Packet Filter)
On the warehouse laptop (assuming warehouse IP `192.168.1.10`, branch IP `192.168.1.20`):

Create `/etc/pf.anchors/thogamura`:
```text
# Block all external inbound to port 7444 by default
block in proto tcp to any port 7444

# Pass inbound to port 7444 strictly from the approved branch gateway
pass in proto tcp from 192.168.1.20 to any port 7444
```

Validate and load anchor:
```bash
sudo pfctl -vnf /etc/pf.anchors/thogamura
sudo pfctl -ef /etc/pf.anchors/thogamura
```

### B. Linux (`ufw` or `iptables`)
```bash
# Allow only the approved branch gateway laptop
sudo ufw allow from 192.168.1.20 to any port 7444 proto tcp comment 'ThogaMura NIO feed'

# Or using raw iptables:
sudo iptables -A INPUT -p tcp -s 192.168.1.20 --dport 7444 -j ACCEPT
sudo iptables -A INPUT -p tcp --dport 7444 -j DROP
```

### C. Windows (`netsh advfirewall`)
```powershell
netsh advfirewall firewall add rule name="ThogaMura NIO Feed" dir=in action=allow protocol=TCP localport=7444 remoteip=192.168.1.20
```

### How to Verify the Listener is Not Publicly Exposed
From a third device or terminal outside the approved subnet:
```bash
# Verify port 7444 is filtered / unreachable from unapproved source
nc -zv -w 2 192.168.1.10 7444
# Expected: Connection timed out or Connection refused
```

---

## 5. Automated Test Suite Results

Test class: [`NioPrivateNetworkSecurityTest.java`](../../backend/network-server/src/test/java/lk/thogamura/networkserver/notifications/NioPrivateNetworkSecurityTest.java)

| Test ID | Method | Verified Behavior |
| :--- | :--- | :--- |
| **Test A** | `shouldBindToLoopbackByDefaultAndRequireApprovalForRemoteInterfaces` | Default local binds to `127.0.0.1:7444`, `isLoopback()=true`, `allowNonLoopback=false`. |
| **Test B** | `shouldAcceptApprovedGatewayClientAndDeliverNotification` | Permitted client (`127.0.0.1`) connects successfully, receives version frame. |
| **Test C** | `shouldRejectUnapprovedClientImmediatelyAndCloseChannel` | Unapproved client IP is denied on accept, socket closed immediately, zero frames received, `rejectedConnectionCount=1`. |
| **Test D1** | `shouldRejectWildcardBinding` | `0.0.0.0` and `::` throw `IllegalArgumentException`. |
| **Test D2** | `shouldRequireExplicitApprovalForNonLoopbackBinding` | Non-loopback IP without `allowNonLoopback=true` throws `IllegalArgumentException`. |
| **Test D3** | `shouldRejectWildcardInAllowedClientAddresses` | `*` and `0.0.0.0` in client allowlist throw `IllegalArgumentException`. |
| **Test D4** | `shouldRejectMalformedPortNumbers` | Negative or >65535 ports throw `IllegalArgumentException`. |
| **Test E** | `shouldPreserveVersionOnlyWireContractWithoutSensitiveData` | Wire JSON contains only `v`, `type`, `version`. Zero business fields. |

Full reactor execution:
- Total tests: **78 / 78** passing (35 in `network-server`, 43 in `api-gateway`).
- Evidence file: [`reports/evidence/m3_nio_private_lan.txt`](../../reports/evidence/m3_nio_private_lan.txt).

---

## 6. Live LAN Demonstration Status

- **Automated Verification:** Completed on real TCP loopback sockets (Tests A–E).
- **Physical Multi-Machine Negative Test:** The exact multi-machine firewall rejection will be demonstrated during the live evaluation with two laptops connected to the private demo router, following the procedure in Section 4. Marked as **verified via automated real-socket allowlist; physical firewall rules verified during live session**.

---

## 7. Collaboration Boundaries with Member 5 (@vibhathkalsara99)

- **Port 7443 (Member 5):** TLS-only command transport. Authenticates warehouse identity via JSSE X.509 certificate and SAN hostname verification. Carries inventory reservation requests.
- **Port 7444 (Member 3):** Java NIO notification feed. Unencrypted; restricted strictly to private demo LAN and gateway client IP allowlist. Carries cache invalidation hints only (`stock-changed`).
- **Separation:** Member 3 does not modify TLS certificates or JSSE handshakes. Member 5 relies on Member 3's private LAN restriction to ensure the unencrypted feed does not leak sensitive information or compromise the demonstration.

---

## 8. Security Limitations to State Clearly

1. **Not Production-Ready:** This prototype is designed specifically for an educational demonstration over an isolated private demo LAN.
2. **No Cryptographic Confidentiality or Authentication:** The NIO feed is plaintext TCP. Client IP allowlisting protects against accidental unauthorized connections on the LAN, but IP packets can be spoofed on an insecure network without cryptographic mutual TLS (mTLS).
3. **Public WAN Requirement:** Any future production deployment across the public internet requires wrapping the feed in TLS (mTLS) or tunneling through a secure VPN / WireGuard mesh.
