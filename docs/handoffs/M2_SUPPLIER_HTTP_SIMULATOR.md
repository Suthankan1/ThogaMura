# Member 2 Handoff — Independent Supplier HTTP Simulator (Issue #31)

- **Owner:** Member 2 (@sandunikarunarathna — Sanduni Karunarathna)
- **Role:** Java URL/HTTP Networking (External Supplier Availability)
- **Module:** `backend/network-server` (`lk.thogamura.networkserver.integration.supplier`)
- **Status:** Complete (Verified with unit, real-socket, and multi-process integration tests)
- **Reference Issue:** [#31 — [M2][W3] Build independent supplier HTTP simulator for real network tests](https://github.com/Suthankan1/ThogaMura/issues/31)
- **Contract:** [SUPPLIER_HTTP_V1.md](../contracts/SUPPLIER_HTTP_V1.md) (v1.0.0 Frozen)
- **Receiving Task:** [#32 — [M2][W3] Implement Java URI/HttpURLConnection supplier availability connector](https://github.com/Suthankan1/ThogaMura/issues/32)

---

## 1. Overview & Java APIs Used

In ThogaMura, when the central warehouse has insufficient stock to fulfill a branch request (e.g., out-of-stock `ITEM-008`), branch staff can query external supplier availability as an **advisory next step**. Supplier availability is strictly read-only and never mutates central PostgreSQL inventory or automatically places orders.

To prove genuine Java URL/HTTP network programming without relying on in-process method shortcuts or simulated state, Member 2 implemented the **Independent Supplier HTTP Simulator** (`SupplierHttpSimulator`):

### Java Standard APIs Used:
- `com.sun.net.httpserver.HttpServer`: Standard JDK HTTP server running independently over TCP sockets.
- `java.net.InetSocketAddress`: Binds the server to loopback (`127.0.0.1`) by default or custom interfaces.
- `java.net.HttpURLConnection` & `java.net.URI` / `java.net.URL`: Standard Java HTTP client used in automated tests to execute real outbound HTTP requests.
- `java.util.concurrent.ExecutorService`: Cached worker thread pool dispatching concurrent client HTTP exchanges.
- `java.lang.ProcessBuilder`: Spawns the simulator in a separate JVM operating system process to guarantee true multi-process network transport.
- `com.fasterxml.jackson.databind.ObjectMapper`: Serializes contract-compliant JSON responses and validates JSON error envelopes.
- `java.time.Instant` & `DateTimeFormatter.ISO_INSTANT`: Formats UTC timestamps (`checkedAt`) according to ISO-8601.

---

## 2. Architecture & Class Layout

```
backend/network-server/src/
├── main/java/lk/thogamura/networkserver/integration/supplier/
│   ├── package-info.java                # Package documentation and architectural boundaries
│   ├── SupplierStockItem.java           # Record modeling synthetic supplier catalog items
│   └── SupplierHttpSimulator.java       # Standalone HTTP server, route handlers, CLI controls
└── test/java/lk/thogamura/networkserver/integration/supplier/
    ├── SupplierHttpSimulatorTest.java   # 19 real-socket HTTP tests (200, 0 units, 404, 503, timeout, malformed)
    └── SupplierHttpSimulatorProcessIntegrationTest.java # Multi-process cross-JVM HTTP integration test
```

---

## 3. Implemented Routes & Wire Contracts

The simulator strictly implements the frozen specifications defined in `docs/contracts/SUPPLIER_HTTP_V1.md`:

### 3.1 Advisory Stock Inquiry: `GET /v1/stock?sku=<sku>`

Headers emitted by the server:
- `Content-Type: application/json; charset=UTF-8`
- `Server: ThogaMura-MockSupplier/1.0`
- `X-Supplier-Source: MOCK_SUPPLIER`

#### Supported Scenarios:

1. **Known Available Stock (HTTP 200 OK):**
   - Query: `GET /v1/stock?sku=ITEM-008`
   ```json
   {
     "sku": "ITEM-008",
     "supplier": "Demo Supplier",
     "availableUnits": 20,
     "estimatedRestockDays": 3,
     "checkedAt": "2026-11-18T13:25:35Z"
   }
   ```

2. **Known Confirmed Zero Quantity (HTTP 200 OK):**
   - Query: `GET /v1/stock?sku=ITEM-008-ZERO` or `GET /v1/stock?sku=ITEM-008&mode=zero`
   ```json
   {
     "sku": "ITEM-008-ZERO",
     "supplier": "Demo Supplier",
     "availableUnits": 0,
     "estimatedRestockDays": 7,
     "checkedAt": "2026-11-18T13:25:35Z"
   }
   ```
   *Critical Distinction:* Confirmed `0` units indicates the supplier catalogs the item but has zero current stock. It is explicitly distinguished from missing (`404`) or outage (`503`).

3. **Unknown SKU (HTTP 404 Not Found):**
   - Query: `GET /v1/stock?sku=UNKNOWN-ITEM-999`
   ```json
   {
     "code": "SKU_NOT_FOUND",
     "message": "Item is not cataloged by supplier"
   }
   ```

4. **Supplier Outage Simulation (HTTP 503 Service Unavailable):**
   - Triggered by: `GET /v1/stock?sku=ITEM-503`, query param `?mode=unavailable`, or CLI command `503`.
   ```json
   {
     "code": "SERVICE_UNAVAILABLE",
     "message": "Supplier inventory system undergoing maintenance"
   }
   ```

5. **Delayed Response Simulation (Network Timeout):**
   - Triggered by: `GET /v1/stock?sku=ITEM-DELAY`, query param `?delayMs=6000`, or `?mode=timeout`.
   - Delays HTTP response by the specified duration (default 6,000 ms, exceeding standard 5,000 ms read timeout), allowing verification of client-side `SocketTimeoutException` handling.

6. **Malformed JSON Test Mode:**
   - Triggered by: `GET /v1/stock?sku=ITEM-MALFORMED`, query param `?mode=malformed`, or CLI command `malformed`.
   - Emits unparseable truncated JSON bytes with `application/json` content-type to verify connector parsing resilience.

7. **Missing / Blank Parameter (HTTP 400 Bad Request):**
   - Query: `GET /v1/stock`
   ```json
   {
     "code": "MISSING_PARAMETER",
     "message": "Query parameter 'sku' is required"
   }
   ```

8. **Unsupported Method (HTTP 405 Method Not Allowed):**
   - Query: `POST /v1/stock`
   ```json
   {
     "code": "METHOD_NOT_ALLOWED",
     "message": "Only GET requests are supported on /v1/stock"
   }
   ```

### 3.2 Health & Identification: `GET /v1/status`

- Query: `GET /v1/status`
```json
{
  "service": "MOCK_SUPPLIER",
  "status": "UP",
  "version": "1.0.0",
  "host": "127.0.0.1",
  "port": 8090,
  "advisoryOnly": true,
  "catalogSize": 4,
  "requestCount": 12,
  "serviceUnavailable": false,
  "malformedPayload": false,
  "responseDelayMs": 0
}
```

---

## 4. How to Run the Simulator

### Option A: Standalone Java Command
```bash
# Compile project
mvn -f backend/network-server/pom.xml compile

# Run on default port 8090
java -cp "backend/network-server/target/classes:$(mvn -f backend/network-server/pom.xml dependency:build-classpath | grep -v '\[INFO\]' | tr '\n' ':')" \
  lk.thogamura.networkserver.integration.supplier.SupplierHttpSimulator 8090 127.0.0.1
```

### Option B: Maven Exec Plugin
```bash
mvn -f backend/network-server/pom.xml exec:java@supplier-simulator
```

### Interactive Console Commands:
```text
==================================================================
  ThogaMura Independent Supplier HTTP Simulator (Member 2)
  Assessed Concept: Java URL/HTTP Networking (Advisory Availability)
  Listening on: http://127.0.0.1:8090
  Stock Route: http://127.0.0.1:8090/v1/stock?sku=ITEM-008
  Status Route: http://127.0.0.1:8090/v1/status
  Identified as: MOCK_SUPPLIER
  NOTICE: Advisory test service only. No access to PostgreSQL.
==================================================================
Interactive Commands:
  status         : Print simulator statistics
  503            : Toggle 503 SERVICE_UNAVAILABLE mode
  malformed      : Toggle malformed JSON response mode
  delay <ms>     : Set response delay in milliseconds (e.g. 'delay 6000')
  reset          : Reset synthetic catalog and flags to defaults
  quit / exit    : Stop the simulator cleanly
==================================================================
```

---

## 5. Automated Test Verification & Results

### Targeted Member 2 Test Suite:
```bash
mvn -f backend/network-server/pom.xml test -Dtest=SupplierHttpSimulatorTest,SupplierHttpSimulatorProcessIntegrationTest
```

**Observed Results:**
```text
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running lk.thogamura.networkserver.integration.supplier.SupplierHttpSimulatorTest
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.511 s -- in SupplierHttpSimulatorTest
[INFO] Running lk.thogamura.networkserver.integration.supplier.SupplierHttpSimulatorProcessIntegrationTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.186 s -- in SupplierHttpSimulatorProcessIntegrationTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] [BUILD SUCCESS]
```

### Full Backend Reactor Verification:
```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn -f backend/pom.xml clean verify
```

**Observed Results:**
```text
[INFO] Reactor Summary for ThogaMura backend reactor 0.1.0-SNAPSHOT:
[INFO] 
[INFO] ThogaMura backend reactor .......................... SUCCESS [  0.041 s]
[INFO] Shared wire protocol constants ..................... SUCCESS [  1.353 s] (21 tests)
[INFO] Java network server ................................ SUCCESS [  9.171 s] (56 tests)
[INFO] HTTP/SSE to Java sockets gateway ................... SUCCESS [ 16.634 s] (43 tests)
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total tests run: 120, Failures: 0, Errors: 0, Skipped: 0
```

---

## 6. Live Demonstration Walkthrough (60–90 Seconds)

To perform an individual demonstration for Member 2:

1. **Terminal 1 — Start the Simulator:**
   ```bash
   mvn -f backend/network-server/pom.xml exec:java@supplier-simulator
   ```
   *Explain:* "Here is the independent supplier HTTP mock process listening on port 8090, separate from the warehouse and gateway."

2. **Terminal 2 — Verify Positive Available Stock (ITEM-008):**
   ```bash
   curl -i "http://127.0.0.1:8090/v1/stock?sku=ITEM-008"
   ```
   *Expected:* HTTP 200 OK, `availableUnits: 20`, `supplier: "Demo Supplier"`.

3. **Terminal 2 — Verify Confirmed Zero Quantity vs Missing:**
   ```bash
   curl -i "http://127.0.0.1:8090/v1/stock?sku=ITEM-008-ZERO"
   ```
   *Expected:* HTTP 200 OK, `availableUnits: 0` (confirmed zero, not missing).
   ```bash
   curl -i "http://127.0.0.1:8090/v1/stock?sku=UNKNOWN-ITEM-XYZ"
   ```
   *Expected:* HTTP 404 Not Found, code `SKU_NOT_FOUND`.

4. **Terminal 2 — Verify Outage & Timeout Failure Modes:**
   ```bash
   curl -i "http://127.0.0.1:8090/v1/stock?sku=ITEM-503"
   ```
   *Expected:* HTTP 503 Service Unavailable, code `SERVICE_UNAVAILABLE`.
   ```bash
   curl --max-time 1 -i "http://127.0.0.1:8090/v1/stock?sku=ITEM-DELAY"
   ```
   *Expected:* `curl: (28) Operation timed out after 1000 milliseconds`.

5. **Terminal 1 — Graceful Shutdown:**
   Type `quit` in Terminal 1. Server releases port 8090 and terminates cleanly.

---

## 7. Next Task

**Issue #32 — [M2][W3] Implement Java URI/HttpURLConnection supplier availability connector**:
Connect the central `network-server` to this simulator via `HttpURLConnection`, validate URLs to prevent SSRF, enforce timeouts and payload limits, map responses to wire protocol `GET_SUPPLIER_STOCK`, and bridge through the API gateway.
