# Network protocol v1 — FROZEN CONTRACT (v1.0.0)

- **Status:** **FROZEN** (2026-11-18)
- **Owners:** Member 1 (@sankharavindu — TCP Transport) & Member 5 (@vibhathkalsara99 — TLS Security)
- **Collaborators:** Member 2 (Supplier HTTP), Member 3 (NIO Feed), Member 4 (Durable Concurrency)
- **Reference ADRs:** [ADR-001](../decisions/DECISION_LOG.md#adr-001--explicit-java-socket-boundary-accepted-for-starter), [ADR-004](../decisions/DECISION_LOG.md#adr-004--tls-identity-policy-accepted), [ADR-006](../decisions/DECISION_LOG.md#adr-006--remove-udp-discovery-and-define-practical-network-features-2026-11-17-accepted-as-v2-design), [ADR-007](../decisions/DECISION_LOG.md#adr-007--shared-network-protocol-v1-http-api-v1-and-security-contracts-freeze-2026-11-18-accepted)

---

## 1. Architectural Scope & Transport Separation

Business commands and cache notifications cross distinct Java networking boundaries between the **Branch Gateway** and the **Central Warehouse Server**:

1. **Business Command Transport (Port 7443 — TCP / JSSE TLS):**
   - Pure hand-written Java `java.net.Socket` and `java.net.ServerSocket` wrapped in JSSE `SSLSocket` and `SSLServerSocket`.
   - Mandatory server-authenticated TLS with strict truststore and hostname verification (ADR-004).
   - Servicing all state-mutating requests and authoritative inventory queries.
2. **Real-Time Notification Feed (Port 7444 — Java NIO):**
   - Pure non-blocking `java.nio.channels.ServerSocketChannel` and `SocketChannel` multiplexed by a single `Selector` event loop.
   - Strictly restricted to private demo LAN and loopback interfaces (Issue #33).
   - Best-effort version-only cache invalidation hints; carries **no** business state or sensitive payload.

Spring Boot REST and Next.js EventSource exist exclusively as browser-to-gateway bridges and do **not** replace or bypass this socket wire protocol.

---

## 2. Framing, Encoding & Limits

- **Delimiter:** Strictly newline-delimited (`\n`, ASCII `0x0A`). An optional preceding carriage return (`\r`, ASCII `0x0D`) must be accepted and stripped during decoding.
- **Character Encoding:** Strictly UTF-8.
- **Maximum Frame Size:** Strictly **65,536 bytes** (64 KB) per frame including the delimiter.
  - If a client socket produces more than 65,536 bytes before encountering a newline, the server must discard the buffer, fail with `FrameTooLargeException`, return error code `VALIDATION_ERROR`, and terminate the connection cleanly.
- **Stream Reconstruction:** A single TCP `read()` is **not** guaranteed to represent a complete frame. Transports must buffer incoming byte streams across partial reads, accumulate fragments, and handle multiple newline-delimited frames arriving in a single read burst.
- **Default Socket Timeouts:**
  - Socket Connection Timeout: **3,000 ms** (3 seconds).
  - Socket Read / `SO_TIMEOUT`: **5,000 ms** (5 seconds).
  - Configurable via `thogamura.transport.connect-timeout-ms` and `thogamura.transport.read-timeout-ms`.

---

## 3. Envelope Specifications

Every message crossing the command socket is a single JSON object.

### 3.1 Request Envelope
Sent from Gateway to Warehouse Server:
```json
{
  "v": 1,
  "type": "<COMMAND_TYPE>",
  "requestId": "<UUIDv4>",
  "data": { ... }
}
```
- `v` (integer, required): Wire protocol version. Must be strictly `1`. Frames with other versions are rejected with `UNSUPPORTED_VERSION`.
- `type` (string, required): One of the frozen command types defined in Section 4.
- `requestId` (string, required): Canonical UUID v4 string generated once per client action. Reused unchanged during network retries.
- `data` (object, optional depending on command): Command-specific payload.

### 3.2 Response Envelope — Accepted / Success
Sent from Warehouse Server to Gateway:
```json
{
  "v": 1,
  "type": "RESULT",
  "requestId": "<UUIDv4>",
  "status": "<STATUS_TYPE>",
  "data": { ... }
}
```
- `type`: Always `"RESULT"`.
- `requestId`: Exactly matches the `requestId` of the originating command.
- `status`: One of `"ACCEPTED"`, `"PONG"`, `"AVAILABLE"`, `"PICKED"`, `"DISPATCHED"`.
- `data`: Result payload.

### 3.3 Response Envelope — Business Rejection
Returned when the command was syntactically valid and processed, but rejected by business rules (e.g. stock shortfall):
```json
{
  "v": 1,
  "type": "RESULT",
  "requestId": "5a3e07fd-158b-4d77-9b97-51844197ed9c",
  "status": "REJECTED",
  "error": {
    "code": "INSUFFICIENT_STOCK",
    "message": "Requested quantity 8 exceeds available stock 2"
  }
}
```

### 3.4 Response Envelope — Protocol / System Error
Returned on validation failures, conflicts, or unexpected internal server exceptions:
```json
{
  "v": 1,
  "type": "RESULT",
  "requestId": "5a3e07fd-158b-4d77-9b97-51844197ed9c",
  "status": "ERROR",
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Malformed JSON or missing required field 'sku'"
  }
}
```

---

## 4. Frozen Command Set

| Command Type | Inbound `data` | Successful `status` | Outbound `data` | Description |
| :--- | :--- | :--- | :--- | :--- |
| **`PING`** | `{}` (optional) | `"PONG"` | `{"serverTime":"<ISO-8601>"}` | Non-mutating diagnostic connectivity check. Verifies TCP/TLS path. |
| **`GET_PRODUCTS`** | `{}` (optional page/filter) | `"ACCEPTED"` | `{"products":[{"sku":"...","name":"...","onHand":10,"reserved":2,"available":8}]}` | Returns authoritative product inventory levels. |
| **`REQUEST_STOCK`** | `{"sku":"ITEM-007","quantity":8}` | `"ACCEPTED"` | `{"requestId":"...","sku":"ITEM-007","quantity":8,"status":"ACCEPTED"}` | Atomically checks availability and increments `reserved`. Rejected if `available < quantity`. |
| **`GET_REQUESTS`** | `{"status":"..."}` (optional) | `"ACCEPTED"` | `{"requests":[{"requestId":"...","sku":"...","quantity":8,"status":"ACCEPTED"}]}` | Returns fulfillment request list. |
| **`MARK_PICKED`** | `{"stockRequestId":"<UUID>"}` | `"PICKED"` | `{"stockRequestId":"...","status":"PICKED"}` | Transitions request state from ACCEPTED $\to$ PICKED. |
| **`MARK_DISPATCHED`** | `{"stockRequestId":"<UUID>"}` | `"DISPATCHED"` | `{"stockRequestId":"...","status":"DISPATCHED"}` | Transitions request state PICKED $\to$ DISPATCHED, decrements `on_hand` and `reserved`, logs movement. |
| **`GET_MOVEMENTS`** | `{}` (optional page) | `"ACCEPTED"` | `{"movements":[{"id":1,"stockRequestId":"...","sku":"...","quantityDelta":-8,"reason":"DISPATCH"}]}` | Returns immutable historical stock movements. |
| **`GET_SUPPLIER_STOCK`** | `{"sku":"ITEM-008"}` | `"AVAILABLE"` / `"UNAVAILABLE"` / `"UNKNOWN"` | `{"sku":"ITEM-008","supplier":"Demo Supplier","reportedQuantity":20,"source":"MOCK_SUPPLIER"}` | Advisory external supplier inquiry via central Java URL/HTTP connection. |

---

## 5. Frozen Error Codes

Every error response must provide an exact error code from this enumeration:

| Error Code | HTTP Status | Definition |
| :--- | :--- | :--- |
| **`VALIDATION_ERROR`** | `400 Bad Request` | Missing required fields, illegal values, or unparseable JSON frame. |
| **`NOT_FOUND`** | `404 Not Found` | Requested SKU, stockRequestId, or movement ID does not exist in PostgreSQL. |
| **`INSUFFICIENT_STOCK`** | `409 Conflict` | Requested quantity exceeds current available inventory (`on_hand - reserved`). |
| **`INVALID_TRANSITION`** | `409 Conflict` | Attempted illegal state transition (e.g. attempting to dispatch an unpicked request). |
| **`DUPLICATE_ID_CONFLICT`** | `409 Conflict` | Command `requestId` was previously used with a different command type or different payload. |
| **`UNSUPPORTED_VERSION`** | `400 Bad Request` | Frame envelope version `v` is not recognized (expected `1`). |
| **`SUPPLIER_UNAVAILABLE`** | `503 Service Unavailable` | Configured external supplier HTTP service is offline, unreachable, or returned 503. |
| **`SUPPLIER_TIMEOUT`** | `504 Gateway Timeout` | Central server's outbound HTTP request to supplier timed out. |
| **`SUPPLIER_UNKNOWN`** | `502 Bad Gateway` | Supplier returned malformed, unparseable, or contradictory response. |
| **`INTERNAL_ERROR`** | `500 Internal Error` | Unexpected server exception. Stack traces and database credentials must never be exposed. |

---

## 6. Durability, Idempotency & Replay Rules

For all mutating commands (`REQUEST_STOCK`, `MARK_PICKED`, `MARK_DISPATCHED`):

1. **Transactional Command Receipts:**
   - In the exact same database transaction that updates `product_stock` or `stock_requests`, the warehouse server computes a SHA-256 fingerprint of the normalized command payload.
   - The server inserts `(command_request_id, command_type, payload_fingerprint, result_json, created_at)` into `command_receipts`.
2. **Deterministic Replay:**
   - If an incoming command's `requestId` matches an existing receipt:
     - If `payload_fingerprint` matches: Immediately replay the recorded `result_json` without re-executing inventory calculations. Replaying an `ACCEPTED` or `REJECTED` command produces the identical response.
     - If `payload_fingerprint` or `command_type` differs: Return `status: "ERROR"`, `error.code: "DUPLICATE_ID_CONFLICT"`.
3. **Ambiguous Network Drops:**
   - If a client's TCP socket drops after submitting a request, the client must retry using the **identical `requestId` and payload**. The server will return the committed outcome without duplicating stock reservations.

---

## 7. Java NIO Notification Feed Specification (Port 7444)

- **Wire Format:** Strictly single-line JSON followed by `\n`:
  ```json
  {"v":1,"type":"CHANGED","version":42}
  ```
- **Payload Privacy Contract:** Strictly prohibited from transmitting SKUs, quantities, customer information, operator IDs, or database primary keys.
- **Best-Effort Delivery:** The NIO feed is an unauthenticated, non-durable cache invalidation channel. Gateways and browsers must never assume continuous delivery; on reconnection or page focus, clients must execute an authoritative REST query (`GET /api/v1/products`) over the TLS path.
- **Memory Bounding:** The NIO server enforces a bounded outbound queue (default 50 frames). Slow clients that fail to consume their buffers are evicted, preserving healthy subscriber throughput.

---

## 8. TLS & Transport Security Specification (Port 7443)

- **Mandatory Server Authentication:** The branch gateway connects to the central warehouse server using Java JSSE `SSLSocketFactory`. The gateway strictly verifies:
  1. The server certificate is signed by the trusted coursework demonstration CA.
  2. The server certificate has not expired.
  3. The target hostname/IP matches the certificate's Subject Alternative Name (SAN).
- **Plaintext Forbidden:** The command listener on port 7443 rejects any non-TLS connection. Plaintext fallback is strictly prohibited.
- **Keystore Isolation:** Generated `.p12` / `.jks` keystores and truststores must remain strictly outside Git tracking.
- **Client Authentication:** Server-authenticated TLS is the required baseline for the MVP. Mutual TLS (mTLS requiring client certificates) is documented as an optional enhancement for advanced demos.

---

## 9. Protocol Governance & Modification Process

This contract is **FROZEN**. No individual student or AI assistant may introduce modifications, new command types, altered envelopes, or modified error codes without:
1. Creating a formal numbered ADR in [docs/decisions/DECISION_LOG.md](../decisions/DECISION_LOG.md).
2. Updating both this specification and [HTTP_API_V1.md](./HTTP_API_V1.md).
3. Obtaining explicit agreement from all five group members.
