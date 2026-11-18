# Member 1 Handoff — Real Java TCP Command Transport (Issue #7)

- **Owner:** Member 1 (@sankharavindu — Sankha Ravindu)
- **Role:** Java TCP Networking
- **Module:** `backend/network-server` (`lk.thogamura.networkserver.transport.tcp`) and `backend/api-gateway` (`lk.thogamura.gateway.network.tcp`)
- **Status:** Complete (Verified with automated unit, socket integration, and separate-JVM process tests)
- **Reference Issue:** [#7 — [M1][W2] Implement real TCP command server and gateway socket client](https://github.com/Suthankan1/ThogaMura/issues/7)
- **Contracts:** [NETWORK_PROTOCOL_V1.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/contracts/NETWORK_PROTOCOL_V1.md) (v1.0.0 Frozen), [HTTP_API_V1.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/contracts/HTTP_API_V1.md), [SECURITY_AND_LIMITATIONS.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/SECURITY_AND_LIMITATIONS.md)

---

## 1. Overview & Core Java Networking Concepts

This work implements the real Java TCP command communication boundary between the Branch Gateway (`api-gateway`) and Central Warehouse Server (`network-server`) across separate operating system processes over real OS TCP sockets.

### Concepts & Mechanics:
1. **TCP Sockets (`java.net.Socket`, `java.net.ServerSocket`):**
   - Endpoints of a two-way communication link between two programs running on the network.
   - Sockets bind to specific network interface addresses (`InetAddress`) and port numbers.
   - Distinct from HTTP or higher-level RPC: standard socket streams operate on raw bytes without built-in message boundaries.

2. **Byte Streams (`InputStream`, `OutputStream`):**
   - TCP provides an unstructured, continuous stream of bytes.
   - A single `Socket.getInputStream().read()` does **not** equal a single discrete message. Data may be fragmented over multiple physical packets or multiple distinct messages may arrive in a single packet.

3. **Message Framing (Bounded Newline JSON):**
   - Delimits discrete application frames using `\n` (ASCII `0x0A`), stripping optional `\r` (`0x0D`).
   - Reuses `TcpFrameReader` and `TcpFrameWriter` from Issue #6.
   - Strictly enforces a maximum bounds limit of **65,536 bytes** (64 KB) per frame, throwing `FrameTooLargeException` and aborting rogue or overflowing buffers to prevent JVM heap exhaustion.

4. **Connection and Read Timeouts:**
   - **Connection Timeout (`socket.connect(endpoint, timeout)`):** Bounds the maximum duration (default 3,000 ms) waiting for the initial TCP 3-way handshake to complete. Prevents threads from hanging indefinitely when target hosts are unreachable or blocked.
   - **Read Timeout (`socket.setSoTimeout(timeout)`):** Bounds the maximum duration (default 5,000 ms) waiting for incoming bytes on `InputStream.read()`. Throws `SocketTimeoutException` if the remote peer fails to transmit within the deadline.

5. **Request/Response Correlation (`requestId`):**
   - Every request envelope carries a canonical UUID v4 `requestId`.
   - The gateway client strictly validates that the response envelope's `requestId` matches the request `requestId`. Any mismatch throws `CommandCorrelationException`.

6. **Later TLS Transport Boundary (Member 5 Integration):**
   - Both `TcpCommandServer` and `TcpCommandClient` accept injected `ServerSocketFactory` and `SocketFactory` abstractions (defaulting to standard JDK socket factories).
   - Member 5 (@vibhathkalsara99) can inject `SSLServerSocketFactory` and `SSLSocketFactory` with X.509 keystore/truststore validation on port 7443 without modifying any business command logic or framing codecs.

---

## 2. Architecture & Class Layout

### Central Warehouse Server (`backend/network-server`):
```
backend/network-server/src/main/java/lk/thogamura/networkserver/transport/tcp/
├── CommandDispatcher.java             # Interface for dispatching validated requests
├── CommandHandler.java                # Functional interface for individual command handling
├── DefaultCommandDispatcher.java      # Dispatches PING returning PONG with ISO-8601 serverTime
├── TcpCommandServerConfig.java        # Configuration, timeouts, and loopback security validation
├── TcpCommandServer.java              # Multi-client ServerSocket listener with bounded thread pool
└── TcpCommandServerLifecycle.java     # Spring Boot SmartLifecycle integration bean
```

### Branch API Gateway Client (`backend/api-gateway`):
```
backend/api-gateway/src/main/java/lk/thogamura/gateway/network/tcp/
├── CommandCorrelationException.java   # Thrown when response requestId does not match request
├── CommandExecutionException.java     # Thrown when server returns status ERROR
├── CommandTimeoutException.java       # Thrown on connect or SO_TIMEOUT read expiration
├── CommandTransportException.java     # Base exception for all wire-level command failures
├── ServerUnavailableException.java    # Thrown when warehouse server is offline / drops connection
├── TcpCommandClientConfig.java        # Client timeout and address parameters
├── TcpCommandClient.java              # Blocking JDK Socket command client with framing & correlation
└── TcpCommandClientConfiguration.java # Spring @Configuration providing client beans
```

---

## 3. Protocol Envelope & Implemented Commands

### Diagnostic `PING` Command:
- **Client Request:**
  ```json
  {"v":1,"type":"PING","requestId":"5a3e07fd-158b-4d77-9b97-51844197ed9c"}
  ```
- **Server Response (`PONG`):**
  ```json
  {
    "v": 1,
    "type": "RESULT",
    "requestId": "5a3e07fd-158b-4d77-9b97-51844197ed9c",
    "status": "PONG",
    "data": {
      "serverTime": "2026-11-18T13:35:00.123456Z"
    }
  }
  ```

### Pluggable Architecture for Business Commands:
`DefaultCommandDispatcher` maintains a concurrent registry of `CommandHandler` implementations:
```java
dispatcher.registerHandler(ProtocolInfo.COMMAND_REQUEST_STOCK, stockHandler);
dispatcher.registerHandler(ProtocolInfo.COMMAND_GET_PRODUCTS, productHandler);
```
This allows Member 4 (@suthankan1) to plug in PostgreSQL transactional stock reservations without altering any TCP transport, thread pool, or socket handling code.

---

## 4. Security Boundary & TLS Readiness

As mandated by `docs/SECURITY_AND_LIMITATIONS.md` and ADR-004:
1. **Plaintext Loopback Restriction:**
   `TcpCommandServerConfig.validate()` verifies that the configured host resolves to a loopback address (`127.0.0.1` / `::1`). Attempting to bind plaintext sockets to non-loopback interfaces throws `IllegalStateException`, preventing accidental plaintext exposure on public or LAN networks.
2. **JSSE TLS Socket Factory Readiness:**
   `TcpCommandServer` accepts `javax.net.ServerSocketFactory` and `TcpCommandClient` accepts `javax.net.SocketFactory`. When Member 5 enables TLS on port 7443, the JSSE factories wrap the exact same socket streams.

---

## 5. Startup and Configuration Instructions

### Configuration Properties:

#### `network-server` (`application.yml`):
```yaml
thogamura:
  command:
    server:
      enabled: ${THOGAMURA_COMMAND_SERVER_ENABLED:true}
      host: ${THOGAMURA_COMMAND_SERVER_HOST:127.0.0.1}
      port: ${THOGAMURA_COMMAND_SERVER_PORT:7443}
      backlog: ${THOGAMURA_COMMAND_SERVER_BACKLOG:50}
      read-timeout-ms: ${THOGAMURA_COMMAND_SERVER_READ_TIMEOUT_MS:5000}
      max-clients: ${THOGAMURA_COMMAND_SERVER_MAX_CLIENTS:50}
      max-frame-bytes: ${THOGAMURA_COMMAND_SERVER_MAX_FRAME_BYTES:65536}
      allow-non-loopback-plaintext: ${THOGAMURA_COMMAND_ALLOW_NON_LOOPBACK_PLAINTEXT:false}
```

#### `api-gateway` (`application.yml`):
```yaml
thogamura:
  command:
    client:
      host: ${THOGAMURA_WAREHOUSE_HOST:${THOGAMURA_COMMAND_HOST:127.0.0.1}}
      port: ${THOGAMURA_COMMAND_PORT:7443}
      connect-timeout-ms: ${THOGAMURA_COMMAND_CONNECT_TIMEOUT_MS:3000}
      read-timeout-ms: ${THOGAMURA_COMMAND_READ_TIMEOUT_MS:5000}
      max-frame-bytes: ${THOGAMURA_COMMAND_MAX_FRAME_BYTES:65536}
```

### Manual Demonstration across Separate Terminal Processes:

#### Terminal 1 — Launch Warehouse Server:
```bash
# Standalone CLI execution of TCP Command Server on loopback port 7443
java -cp backend/network-server/target/classes:backend/protocol/target/classes:$(mvn -f backend/network-server/pom.xml dependency:build-classpath | grep -v '\[' | tr '\n' ':') \
  lk.thogamura.networkserver.transport.tcp.TcpCommandServer 7443 127.0.0.1
```
Output:
```text
SERVER_READY:7443
```

#### Terminal 2 — Connect with Gateway Socket Client / nc:
```bash
echo '{"v":1,"type":"PING","requestId":"5a3e07fd-158b-4d77-9b97-51844197ed9c"}' | nc 127.0.0.1 7443
```
Output:
```json
{"v":1,"type":"RESULT","requestId":"5a3e07fd-158b-4d77-9b97-51844197ed9c","status":"PONG","data":{"serverTime":"2026-11-18T13:36:00.000Z"}}
```

---

## 6. Test Verification & Actual Results

### Full Backend Reactor Verification:
```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn -f backend/pom.xml clean test
```

### Actual Results:
```text
-------------------------------------------------------
 T E S T S
-------------------------------------------------------
[INFO] protocol: 21 tests passed (0 failures, 0 errors)
[INFO] network-server: 39 tests passed (0 failures, 0 errors)
[INFO] api-gateway: 56 tests passed (0 failures, 0 errors)
[INFO] Total tests run: 116, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Specific Integration & Separate Process Scenarios Covered:
1. `SeparateProcessTcpCommandIntegrationTest`:
   - Spawns `TcpCommandServer` in a distinct OS process using `ProcessBuilder`.
   - Asserts distinct PIDs (`serverPid != currentPid`).
   - Exchanges `PING` and correlated `PONG` with ISO-8601 `serverTime` over real loopback TCP socket.
   - Forcibly kills the server process and verifies immediate connection failure (`ConnectException`).
2. `TcpCommandServerTest`:
   - Enforces loopback security restriction on plaintext server binding.
   - Detects and cleanly rejects oversized frames (> 64 KB) with `VALIDATION_ERROR`.
   - Handles protocol version mismatch (`v: 2`) with `UNSUPPORTED_VERSION`.
   - Handles multiple sequential commands on single persistent TCP connection.
   - Handles abrupt client disconnects without worker thread or server socket crashes.
3. `TcpCommandClientTest`:
   - Enforces strict `requestId` correlation, throwing `CommandCorrelationException` on mismatch.
   - Throws `ServerUnavailableException` when warehouse server is offline.
   - Enforces bounded `SO_TIMEOUT` read timeout, throwing `CommandTimeoutException`.
   - Detects premature server stream closure.
4. `TcpCommandClientIntegrationTest`:
   - Reassembles response frames fragmented across physical network packet boundaries.
   - Verifies clean socket closure across successive requests with zero descriptor leaks.

---

## 7. Next Integration Step

- **Member 4 (@suthankan1):** Implement atomic inventory reservations (`REQUEST_STOCK`, `GET_PRODUCTS`, `MARK_PICKED`, `MARK_DISPATCHED`) by registering handlers with `CommandDispatcher`.
- **Member 5 (@vibhathkalsara99):** Supply `SSLServerSocketFactory` and `SSLSocketFactory` to enable validated JSSE TLS on port 7443.
