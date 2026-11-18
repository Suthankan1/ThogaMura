# Member 2 Handoff — Java HTTP Supplier Connector (Issue #32)

- **Owner:** Member 2 (@sandunikarunarathna — Sanduni Karunarathna)
- **Role:** Java URL/HTTP Networking (External Supplier Availability)
- **Modules:**
  - `backend/protocol` (`lk.thogamura.protocol`)
  - `backend/network-server` (`lk.thogamura.networkserver.integration.supplier`)
  - `backend/api-gateway` (`lk.thogamura.gateway.web`)
- **Status:** Complete (Verified with unit tests, wire-level mock tests, gateway controller tests, and multi-tier end-to-end integration tests)
- **Reference Issue:** [#32 — [M2][W3] Implement Java URI/HttpURLConnection supplier availability connector](https://github.com/Suthankan1/ThogaMura/issues/32)
- **Contracts:**
  - [SUPPLIER_HTTP_V1.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/contracts/SUPPLIER_HTTP_V1.md) (v1.0.0 Frozen)
  - [NETWORK_PROTOCOL_V1.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/contracts/NETWORK_PROTOCOL_V1.md) (v1.0.0 Frozen)
  - [HTTP_API_V1.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/contracts/HTTP_API_V1.md) (v1.0.0 Frozen)
- **Preceding Task:** [#31 — [M2][W3] Build independent supplier HTTP simulator for real network tests](https://github.com/Suthankan1/ThogaMura/issues/31) (PR #46)
- **Receiving Task:** Issue #30 — Supplier failure handling in the frontend (after Issue #13 is ready)

---

## 1. Overview & Architectural Boundaries

In ThogaMura, when central warehouse stock is insufficient for a branch request, warehouse and branch staff need visibility into external supplier availability as an **advisory query**. This inquiry enables branch staff to make informed operational decisions without guessing.

### Strict Architectural Boundaries:
1. **Advisory & Read-Only:** The supplier query is strictly advisory and read-only. It never modifies central warehouse inventory, never writes to PostgreSQL, and never initiates automated purchasing.
2. **Dedicated Separate Process:** The external supplier is represented by the independent `SupplierHttpSimulator` (from Issue #31) running over real HTTP sockets in a separate JVM process or network endpoint.
3. **Multi-Tier Request Flow:**
   ```
   [Browser / Client]
          │  HTTP GET /api/v1/suppliers/availability?sku=ITEM-008
          ▼
   [api-gateway (JVM 1)]
          │  TCP Command (port 7443 / plaintext 7444 loopback test)
          │  {"command":"GET_SUPPLIER_STOCK", "sku":"ITEM-008", ...}
          ▼
   [network-server (JVM 2)]
          │  SupplierCommandHandler -> DefaultSupplierHttpClient
          │  Java java.net.HttpURLConnection (HTTP GET /v1/stock?sku=ITEM-008)
          ▼
   [SupplierHttpSimulator (JVM 3 / External Service)]
          │  JSON Response: {"sku":"ITEM-008","availableUnits":20,...}
          ▼
   [network-server (JVM 2)]
          │  Correlates requestId and returns JSON frame
          ▼
   [api-gateway (JVM 1)]
          │  Maps result code and emits HTTP 200 / 404 / 502 / 504
          ▼
   [Browser / Client]
   ```
4. **Member Workload Separation:**
   - **Member 1:** Owns the raw TCP command listener and gateway TCP socket transport.
   - **Member 2 (This Workload):** Owns the Java URL/HTTP client (`URI`, `URL`, `HttpURLConnection`), supplier SSRF defense, payload parsing, HTTP status resolution, and the gateway REST controller route.
   - **Member 4:** Owns the PostgreSQL transactional inventory reservation. Member 2 code never connects to PostgreSQL or calls reservation services.

---

## 2. Java Networking APIs & Implementation Details

To fulfill the Assignment 02 requirement for authentic Java `java.net` networking (without high-level HTTP abstractions like Apache HttpClient, OkHttp, WebClient, or Spring RestTemplate), Member 2 implemented `DefaultSupplierHttpClient` using core JDK networking APIs:

### 2.1 Core Classes & Roles
- **`java.net.URI` & `java.net.URL`:**
  - `URI.create(...)` validates and creates the hierarchical URI from the configured base URL.
  - Safe query construction: `URLEncoder.encode(sku, StandardCharsets.UTF_8)` escapes special characters.
  - URI is validated and converted via `uri.toURL()` to obtain standard connection objects.
- **`java.net.HttpURLConnection` & `java.net.HttpsURLConnection`:**
  - Opened via `(HttpURLConnection) url.openConnection()`.
  - Configured with explicit timeouts:
    - `setConnectTimeout(config.getConnectTimeoutMs())` (default 3,000 ms).
    - `setReadTimeout(config.getReadTimeoutMs())` (default 5,000 ms).
  - Explicit HTTP method: `setRequestMethod("GET")`.
  - Request headers:
    - `Accept: application/json`
    - `User-Agent: ThogaMura-WarehouseServer/1.0`
  - Explicit redirect defense: `setInstanceFollowRedirects(false)` (prevents open-redirect SSRF pivots).
  - Strict TLS/HTTPS certificate validation is preserved; no custom `TrustManager` bypass is used.
- **Bounded Stream Reading:**
  - Responses (both `getInputStream()` on 2xx and `getErrorStream()` on non-2xx) are consumed using an explicit byte buffer bounded at 16,384 bytes (`16 KB`).
  - If a supplier returns more than 16 KB, reading terminates immediately and throws a `SupplierPayloadTooLargeException` to prevent memory exhaustion / DoS attacks.
- **Connection Resource Cleanup:**
  - Streams are wrapped in `try-with-resources`.
  - `connection.disconnect()` is explicitly called in a `finally` block to ensure socket resources and underlying file descriptors are freed cleanly.

---

## 3. Protocol & Gateway Contract Integration

### 3.1 Network Protocol Commands (`NETWORK_PROTOCOL_V1.md`)
- Added protocol status constants to `ProtocolInfo`:
  - `STATUS_AVAILABLE = "AVAILABLE"`
  - `STATUS_UNAVAILABLE = "UNAVAILABLE"`
  - `STATUS_UNKNOWN = "UNKNOWN"`
- Command format:
  ```json
  {
    "version": 1,
    "requestId": "550e8400-e29b-41d4-a716-446655440000",
    "command": "GET_SUPPLIER_STOCK",
    "sku": "ITEM-008",
    "timestamp": 1700000000000
  }
  ```
- Success response format (Known positive quantity):
  ```json
  {
    "version": 1,
    "requestId": "550e8400-e29b-41d4-a716-446655440000",
    "status": "AVAILABLE",
    "reportedQuantity": 20,
    "supplierName": "Demo Supplier",
    "estimatedRestockDays": 3,
    "checkedAt": "2026-11-18T13:25:35Z"
  }
  ```
- Confirmed zero quantity response:
  ```json
  {
    "version": 1,
    "requestId": "550e8400-e29b-41d4-a716-446655440000",
    "status": "AVAILABLE",
    "reportedQuantity": 0,
    "supplierName": "Demo Supplier",
    "estimatedRestockDays": 7,
    "checkedAt": "2026-11-18T13:25:35Z"
  }
  ```
- Unknown SKU response (Supplier 404):
  ```json
  {
    "version": 1,
    "requestId": "550e8400-e29b-41d4-a716-446655440000",
    "status": "UNKNOWN",
    "errorCode": "NOT_FOUND",
    "message": "Item is not cataloged by supplier"
  }
  ```
- Supplier outage / error response (Supplier 503 / timeout / offline):
  ```json
  {
    "version": 1,
    "requestId": "550e8400-e29b-41d4-a716-446655440000",
    "status": "UNAVAILABLE",
    "errorCode": "SERVICE_UNAVAILABLE",
    "message": "Supplier inventory system undergoing maintenance"
  }
  ```

### 3.2 Gateway REST API (`HTTP_API_V1.md`)
Exposed endpoint in `api-gateway`:
`GET /api/v1/suppliers/availability?sku={sku}`

Mapped HTTP Statuses:
- **HTTP 200 OK:** Status `AVAILABLE` (with `availableUnits >= 0`). Note: confirmed zero stock returns HTTP 200 OK with `availableUnits: 0` (authoritative response per `SUPPLIER_HTTP_V1.md` Section 3.2).
- **HTTP 404 Not Found:** Status `UNKNOWN` with `code: "SKU_NOT_FOUND"`.
- **HTTP 400 Bad Request:** Missing or malformed SKU query parameter (`code: "INVALID_SKU"`).
- **HTTP 502 Bad Gateway:** Status `UNAVAILABLE` when supplier returns 503, malformed JSON, or connection fails (`code: "SUPPLIER_OUTAGE"`).
- **HTTP 504 Gateway Timeout:** Status `UNAVAILABLE` due to supplier timeout (`code: "SUPPLIER_TIMEOUT"`).

---

## 4. Failure Modes & Edge Cases Handled

| Scenario | Supplier / Network Condition | Client Action / Result | Gateway Status | Contract Compliance |
|---|---|---|---|---|
| **Stock Available** | HTTP 200, `availableUnits: 20` | Returns `STATUS_AVAILABLE`, `reportedQuantity: 20` | `200 OK` | `SUPPLIER_HTTP_V1.md` §3.1 |
| **Confirmed Zero** | HTTP 200, `availableUnits: 0` | Returns `STATUS_AVAILABLE`, `reportedQuantity: 0` | `200 OK` | Distinct from 404/503 |
| **Unknown SKU** | HTTP 404 `SKU_NOT_FOUND` | Returns `STATUS_UNKNOWN`, `errorCode: "NOT_FOUND"` | `404 Not Found` | Never reported as 0 stock |
| **Supplier Outage** | HTTP 503 `SERVICE_UNAVAILABLE` | Returns `STATUS_UNAVAILABLE`, `errorCode: "SERVICE_UNAVAILABLE"` | `502 Bad Gateway` | Never reported as 0 stock |
| **Network Refusal** | Connection refused (port down) | Returns `STATUS_UNAVAILABLE`, `errorCode: "OFFLINE"` | `502 Bad Gateway` | Safe graceful degradation |
| **Read Timeout** | Server accepts socket but hangs | `SocketTimeoutException` caught, returns `errorCode: "TIMEOUT"` | `504 Gateway Timeout` | Bounded read timeout (5s) |
| **Connect Timeout** | Non-routable IP hangs connect | `SocketTimeoutException` caught, returns `errorCode: "TIMEOUT"` | `504 Gateway Timeout` | Bounded connect timeout (3s) |
| **Malformed JSON** | Supplier returns invalid JSON text | JSON parse fails, returns `errorCode: "BAD_RESPONSE"` | `502 Bad Gateway` | Strict JSON parse |
| **Oversized Response** | Payload exceeds 16 KB | Stream terminates, returns `errorCode: "PAYLOAD_TOO_LARGE"` | `502 Bad Gateway` | Prevents OOM DoS |
| **Invalid SKU** | Contains illegal characters (`../`) | Rejected before network call (`IllegalArgumentException`) | `400 Bad Request` | Regex `^[A-Za-z0-9_-]{1,64}$` |
| **Unapproved URL** | Host outside allowlist (e.g. `169.254.169.254`) | Rejected on validation (`SecurityException`) | `502 Bad Gateway` | SSRF prevention |

---

## 5. Security & SSRF Defense

1. **No User-Controlled Target Hosts:**
   The gateway query accepts only `sku`. It does not accept target URLs, hostnames, or ports from user input.
2. **Strict Host Allowlist:**
   The base URL is loaded exclusively from server configuration (`thogamura.supplier.base-url`). `SupplierClientConfig` validates the URL scheme (`http` or `https`) and checks host against an approved set (`127.0.0.1`, `localhost`, `::1`, or configured domain names).
3. **Redirect Disabling:**
   `setInstanceFollowRedirects(false)` prevents a malicious or compromised supplier endpoint from redirecting the warehouse client to internal cloud metadata endpoints (e.g., `169.254.169.254`).
4. **Input Sanitization & URL Encoding:**
   SKUs are validated against `^[A-Za-z0-9_-]{1,64}$` and percent-encoded with UTF-8 before being appended to the query string.
5. **Payload Bounding:**
   Maximum read length is capped at 16,384 bytes, preventing memory exhaustion from infinite streams or large files.

---

## 6. Automated Test Verification

All tests run automated in Maven without relying on external third-party services:

### 6.1 Unit & Direct Socket Tests
1. **`DefaultSupplierHttpClientTest` (11 tests, all passing):**
   - `testQueryStockAvailablePositiveQuantity()`: Verifies HTTP 200 with positive units.
   - `testQueryStockConfirmedZeroQuantity()`: Verifies HTTP 200 with 0 units returning `STATUS_AVAILABLE`.
   - `testQueryStockUnknownSku404()`: Verifies 404 returns `STATUS_UNKNOWN` and `NOT_FOUND`.
   - `testQueryStockServiceUnavailable503()`: Verifies 503 returns `STATUS_UNAVAILABLE`.
   - `testQueryStockConnectionRefused()`: Verifies offline supplier returns `STATUS_UNAVAILABLE` and `OFFLINE`.
   - `testQueryStockReadTimeout()`: Verifies delayed response hits read timeout and returns `TIMEOUT`.
   - `testQueryStockMalformedJson()`: Verifies corrupted JSON returns `BAD_RESPONSE`.
   - `testQueryStockOversizedPayloadExceedingLimit()`: Verifies payloads > 16 KB are aborted.
   - `testQueryStockInvalidSkuRejected()`: Verifies malicious/invalid SKU rejected locally.
   - `testUnapprovedSupplierUrlRejectedBySecurity()`: Verifies arbitrary external host rejected for SSRF.
   - `testQueryStockWithRedirectDoesNotFollow()`: Verifies 302 redirect is treated as error, not followed.

2. **`SupplierCommandHandlerTest` (2 tests, all passing):**
   - `testHandleValidCommandSuccess()`: Verifies command dispatcher integration.
   - `testHandleMissingSkuError()`: Verifies command payload validation.

3. **`SupplierControllerTest` (8 tests, all passing):**
   - `testQuerySupplierAvailabilitySuccess()`: HTTP 200 OK mapping.
   - `testQuerySupplierAvailabilityConfirmedZero()`: HTTP 200 OK mapping for 0 units.
   - `testQuerySupplierAvailabilityNotFound()`: HTTP 404 mapping for unknown SKU.
   - `testQuerySupplierAvailabilityOutage()`: HTTP 502 mapping for supplier outage.
   - `testQuerySupplierAvailabilityTimeout()`: HTTP 504 mapping for timeout.
   - `testQuerySupplierAvailabilityInvalidSkuFormat()`: HTTP 400 mapping for malformed SKU.
   - `testQuerySupplierAvailabilityBlankSku()`: HTTP 400 mapping for blank SKU.
   - `testQuerySupplierAvailabilityTransportFailure()`: HTTP 502 mapping on network server unreachable.

4. **`SupplierNetworkFlowIntegrationTest` (4 tests, all passing):**
   - `testEndToEndSupplierStockAvailable()`: Full loop (TcpCommandClient -> TcpCommandServer -> SupplierCommandHandler -> DefaultSupplierHttpClient -> SupplierHttpSimulator).
   - `testEndToEndSupplierConfirmedZeroStock()`: Full loop confirming 0 units.
   - `testEndToEndSupplierUnknownSku()`: Full loop returning 404 `NOT_FOUND`.
   - `testEndToEndSupplierServiceUnavailable()`: Full loop returning 503 `SERVICE_UNAVAILABLE`.

### 6.2 Full Build Execution Output
```text
[INFO] Reactor Summary for ThogaMura backend reactor 0.1.0-SNAPSHOT:
[INFO]
[INFO] ThogaMura backend reactor .......................... SUCCESS [  0.044 s]
[INFO] Shared wire protocol constants ..................... SUCCESS [  1.351 s]
[INFO] Java network server ................................ SUCCESS [ 15.032 s]
[INFO] HTTP/SSE to Java sockets gateway ................... SUCCESS [ 24.035 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```

---

## 7. Manual Network Demonstration Instructions

To demonstrate live multi-process HTTP and TCP traffic across three terminals:

### Step 1: Start Independent Supplier HTTP Simulator
In Terminal 1:
```bash
mvn -f backend/network-server/pom.xml test-compile exec:java \
  -Dexec.mainClass="lk.thogamura.networkserver.integration.supplier.SupplierHttpSimulator" \
  -Dexec.args="--port 8089"
```

### Step 2: Start Central Warehouse Network Server
In Terminal 2:
```bash
mvn -f backend/network-server/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments="--thogamura.supplier.base-url=http://127.0.0.1:8089"
```

### Step 3: Start API Gateway
In Terminal 3:
```bash
mvn -f backend/api-gateway/pom.xml spring-boot:run
```

### Step 4: Execute Live Advisory Inquiries

1. **Query known in-stock item (`ITEM-008`):**
   ```bash
   curl -i http://localhost:8080/api/v1/suppliers/availability?sku=ITEM-008
   ```
   *Expected:* HTTP 200 OK with `availableUnits: 20`.

2. **Query confirmed zero-stock item (`ITEM-008-ZERO`):**
   ```bash
   curl -i http://localhost:8080/api/v1/suppliers/availability?sku=ITEM-008-ZERO
   ```
   *Expected:* HTTP 200 OK with `availableUnits: 0` (authoritative response, not 404).

3. **Query unknown SKU (`UNKNOWN-ITEM`):**
   ```bash
   curl -i http://localhost:8080/api/v1/suppliers/availability?sku=UNKNOWN-ITEM
   ```
   *Expected:* HTTP 404 Not Found with `SKU_NOT_FOUND`.

4. **Trigger supplier outage:**
   In Terminal 1 (Simulator), type `503` and press Enter. Then in Terminal 4:
   ```bash
   curl -i http://localhost:8080/api/v1/suppliers/availability?sku=ITEM-008
   ```
   *Expected:* HTTP 502 Bad Gateway with `SUPPLIER_OUTAGE`.

---

## 8. Summary of Files Changed

- `backend/protocol/src/main/java/lk/thogamura/protocol/ProtocolInfo.java` (Added `STATUS_AVAILABLE`, `STATUS_UNAVAILABLE`, `STATUS_UNKNOWN`)
- `backend/network-server/src/main/resources/application.yml` (Added `thogamura.supplier` config properties)
- `backend/network-server/src/main/java/lk/thogamura/networkserver/integration/supplier/SupplierClientConfig.java`
- `backend/network-server/src/main/java/lk/thogamura/networkserver/integration/supplier/SupplierStockResponse.java`
- `backend/network-server/src/main/java/lk/thogamura/networkserver/integration/supplier/SupplierHttpClient.java`
- `backend/network-server/src/main/java/lk/thogamura/networkserver/integration/supplier/DefaultSupplierHttpClient.java`
- `backend/network-server/src/main/java/lk/thogamura/networkserver/integration/supplier/SupplierCommandHandler.java`
- `backend/network-server/src/main/java/lk/thogamura/networkserver/integration/supplier/SupplierConfiguration.java`
- `backend/network-server/src/main/java/lk/thogamura/networkserver/transport/tcp/DefaultCommandDispatcher.java`
- `backend/network-server/src/test/java/lk/thogamura/networkserver/integration/supplier/DefaultSupplierHttpClientTest.java`
- `backend/network-server/src/test/java/lk/thogamura/networkserver/integration/supplier/SupplierCommandHandlerTest.java`
- `backend/network-server/src/test/java/lk/thogamura/networkserver/integration/supplier/SupplierNetworkFlowIntegrationTest.java`
- `backend/api-gateway/src/main/java/lk/thogamura/gateway/web/SupplierController.java`
- `backend/api-gateway/src/test/java/lk/thogamura/gateway/web/SupplierControllerTest.java`
- `docs/handoffs/M2_SUPPLIER_HTTP_CONNECTOR.md` (This handoff document)
