# Supplier availability HTTP contract v1 — FROZEN CONTRACT (v1.0.0)

- **Status:** **FROZEN** (2026-11-18)
- **Owner:** Member 2 (@sandunikarunarathna — Supplier HTTP Integration)
- **Assessed Concept:** Java `java.net.URI`, `java.net.URL`, `HttpURLConnection` / `HttpsURLConnection`, DNS resolution, and HTTP status handling.
- **Reference Contracts:** [NETWORK_PROTOCOL_V1.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/contracts/NETWORK_PROTOCOL_V1.md), [HTTP_API_V1.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/contracts/HTTP_API_V1.md)

---

## 1. Architectural Role & Execution Topology

```text
Shop Browser (Next.js)
       │ HTTP GET /api/v1/suppliers/availability?sku=ITEM-008
       ▼
Branch API Gateway
       │ Custom Java TCP Command over TLS: GET_SUPPLIER_STOCK (:7443)
       ▼
Central Warehouse Network Server (Process 1)
       │ Outbound Java java.net.HttpURLConnection (GET /v1/stock?sku=ITEM-008)
       ▼
Independent Supplier Mock Service (Process 2 — Port 8090)
```

1. **Separation of Processes:** The supplier service runs as an independent mock process (e.g. at `http://127.0.0.1:8090`), proving real network I/O across process boundaries.
2. **SSRF Prevention:** The target base URL is strictly read from environment configuration (`THOGAMURA_SUPPLIER_BASE_URL`). The server builds the URL using `java.net.URI` with strict URL encoding for the `sku` parameter. Target host, port, or scheme can **never** be supplied by a user or client parameter.
3. **Strictly Advisory:** Supplier availability is read-only. It **never** mutates central inventory, reserves stock, or places financial orders.

---

## 2. Wire Request & Transport Settings

- **HTTP Method:** `GET`
- **Path:** `/v1/stock`
- **Query Parameter:** `sku=<encoded_sku>` (e.g. `/v1/stock?sku=ITEM-008`)
- **Headers Sent by Server:**
  - `Accept: application/json`
  - `User-Agent: ThogaMura-Warehouse-SupplierClient/1.0`
- **Finite Timeouts:**
  - Connect Timeout: **3,000 ms**
  - Read Timeout: **5,000 ms**
- **Payload Size Bound:** Maximum **16,384 bytes** (16 KB) read before JSON parsing. Larger responses must be aborted immediately to prevent memory exhaustion attacks.
- **Redirect Policy:** `HttpURLConnection.setFollowRedirects(false)`. Automatic redirects to unverified origins are prohibited.

---

## 3. Frozen Response Schemas

### 3.1 Known SKU with Available Units (HTTP 200 OK)
```json
{
  "sku": "ITEM-008",
  "supplier": "Demo Supplier",
  "availableUnits": 20,
  "estimatedRestockDays": 3,
  "checkedAt": "2026-11-18T10:00:00Z"
}
```
- **Gateway / UI Mapping:** Returns status `"AVAILABLE"`, `reportedQuantity: 20`.

### 3.2 Known SKU with Confirmed Zero Units (HTTP 200 OK)
```json
{
  "sku": "ITEM-008",
  "supplier": "Demo Supplier",
  "availableUnits": 0,
  "estimatedRestockDays": 7,
  "checkedAt": "2026-11-18T10:00:00Z"
}
```
- **Gateway / UI Mapping:** Returns status `"AVAILABLE"`, `reportedQuantity: 0`.
- **Critical Distinction:** A confirmed `0` indicates the supplier carries the item but currently has zero units in stock. It must **not** be confused with an unknown or missing state.

### 3.3 Unknown SKU (HTTP 404 Not Found)
```json
{
  "code": "SKU_NOT_FOUND",
  "message": "Item is not cataloged by supplier"
}
```
- **Gateway / UI Mapping:** Returns status `"UNKNOWN"`, `reportedQuantity: null`. Error code: `NOT_FOUND`.

### 3.4 Supplier Outage / Down (HTTP 503 or Connection Refused)
```json
{
  "code": "SERVICE_UNAVAILABLE",
  "message": "Supplier inventory system undergoing maintenance"
}
```
- **Gateway / UI Mapping:** Returns status `"UNAVAILABLE"`, `reportedQuantity: null`. Error code: `SUPPLIER_UNAVAILABLE`.

### 3.5 Network Connect / Read Timeout
- When the socket exceeds 3s connect or 5s read deadline:
- **Gateway / UI Mapping:** Returns status `"UNAVAILABLE"`, error code: `SUPPLIER_TIMEOUT`. The UI informs the operator that the supplier could not be reached within the deadline.
