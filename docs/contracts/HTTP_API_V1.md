# HTTP API v1 — Spring Boot Gateway Contract — FROZEN CONTRACT (v1.0.0)

- **Status:** **FROZEN** (2026-11-18)
- **Owners:** Member 2 (@sandunikarunarathna — UI / REST) & Member 1 (@sankharavindu — TCP Gateway Adapter)
- **Base URL:** `http://localhost:8080/api/v1` (Next.js proxies via `/api/v1/:path*`)
- **Reference Contracts:** [NETWORK_PROTOCOL_V1.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/contracts/NETWORK_PROTOCOL_V1.md), [SUPPLIER_HTTP_V1.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/contracts/SUPPLIER_HTTP_V1.md)

---

## 1. Architectural Role & Boundary

The Spring Boot API Gateway serves as the **browser-to-TCP network bridge**:
- Browsers cannot open raw Java TCP/TLS sockets or Java NIO channels.
- The Next.js frontend communicates with the Gateway strictly over standard HTTP REST and Server-Sent Events (SSE).
- The Gateway translates incoming HTTP REST requests into custom **Java Wire Protocol v1** frames over validated TLS to the warehouse server on port 7443.
- The Gateway connects to the warehouse Java NIO feed on port 7444 and broadcasts cache invalidations to browsers over SSE (`/api/v1/events`).
- **No Database Shortcuts:** The Gateway does **not** connect directly to PostgreSQL. Only `network-server` accesses the database.

---

## 2. Frozen Endpoint Catalog

### 2.1 Metadata & Observability Endpoints

#### `GET /api/v1/meta`
Returns gateway operational metadata and scaffold indicators.
- **Response (200 OK):**
  ```json
  {
    "service": "thogamura-api-gateway",
    "version": "0.1.0-SNAPSHOT",
    "scaffold": false,
    "timestamp": "2026-11-18T10:00:00Z"
  }
  ```

#### `GET /api/v1/network-status`
Returns live 3-hop diagnostic health across the complete network pipeline.
- **Response (200 OK):**
  ```json
  {
    "centralHost": "127.0.0.1",
    "nioPort": 7444,
    "nioFeed": {
      "enabled": true,
      "status": "CONNECTED",
      "connected": true,
      "lastReceivedVersion": 42,
      "notificationCount": 1,
      "reconnectCount": 0
    },
    "sse": {
      "activeClients": 1,
      "broadcastCount": 1,
      "lastBroadcastVersion": 42
    },
    "tlsCommand": {
      "status": "OPERATIONAL",
      "owner": "Member 5"
    }
  }
  ```

#### `GET /api/v1/events`
Server-Sent Events (SSE) stream for real-time cache invalidation hints.
- **Headers:** `Accept: text/event-stream`
- **Wire Stream Format:**
  ```text
  event: stock-changed
  data: 42
  ```
- **Rule:** The `data` field contains **only** a monotonically increasing change version (or empty string). The browser treats this strictly as a cache invalidation hint and refetches authoritative records via REST (`GET /api/v1/products`).

---

### 2.2 Product Inventory Endpoints

#### `GET /api/v1/products`
Lists all products with authoritative available stock balances from PostgreSQL.
- **Gateway Mapping:** Translates to wire command `GET_PRODUCTS`.
- **Response (200 OK):**
  ```json
  [
    {
      "sku": "ITEM-007",
      "name": "Organic Carrots",
      "onHand": 10,
      "reserved": 2,
      "available": 8
    },
    {
      "sku": "ITEM-008",
      "name": "Red Onions",
      "onHand": 0,
      "reserved": 0,
      "available": 0
    }
  ]
  ```

---

### 2.3 Stock Request & Fulfillment Endpoints

#### `POST /api/v1/requests`
Submits a stock reservation request for a single SKU.
- **Gateway Mapping:** Translates to wire command `REQUEST_STOCK`.
- **Headers:**
  - `Content-Type: application/json`
  - `Idempotency-Key: <UUIDv4>` (Required. Translated directly into TCP `requestId`).
- **Request Body:**
  ```json
  {
    "sku": "ITEM-007",
    "quantity": 8
  }
  ```
- **Response — Accepted (201 Created):**
  ```json
  {
    "requestId": "5a3e07fd-158b-4d77-9b97-51844197ed9c",
    "status": "ACCEPTED",
    "sku": "ITEM-007",
    "quantity": 8
  }
  ```
- **Response — Insufficient Stock (409 Conflict):**
  ```json
  {
    "code": "INSUFFICIENT_STOCK",
    "message": "Requested quantity 8 exceeds available stock 2",
    "requestId": "5a3e07fd-158b-4d77-9b97-51844197ed9c"
  }
  ```
- **Response — Conflicting Replay (409 Conflict):**
  ```json
  {
    "code": "DUPLICATE_ID_CONFLICT",
    "message": "Idempotency key previously used with different payload",
    "requestId": "5a3e07fd-158b-4d77-9b97-51844197ed9c"
  }
  ```

#### `GET /api/v1/requests`
Lists all fulfillment requests.
- **Gateway Mapping:** Translates to wire command `GET_REQUESTS`.
- **Query Parameters:** `status` (optional: `ACCEPTED`, `REJECTED`, `PICKED`, `DISPATCHED`).
- **Response (200 OK):**
  ```json
  [
    {
      "requestId": "5a3e07fd-158b-4d77-9b97-51844197ed9c",
      "sku": "ITEM-007",
      "quantity": 8,
      "status": "ACCEPTED",
      "createdAt": "2026-11-18T09:30:00Z"
    }
  ]
  ```

#### `POST /api/v1/requests/{id}/pick`
Transitions request status from ACCEPTED $\to$ PICKED.
- **Gateway Mapping:** Translates to wire command `MARK_PICKED`.
- **Response (200 OK):**
  ```json
  {
    "stockRequestId": "5a3e07fd-158b-4d77-9b97-51844197ed9c",
    "status": "PICKED"
  }
  ```

#### `POST /api/v1/requests/{id}/dispatch`
Transitions request status from PICKED $\to$ DISPATCHED, decrements `on_hand` and `reserved`, and creates an immutable movement record.
- **Gateway Mapping:** Translates to wire command `MARK_DISPATCHED`.
- **Response (200 OK):**
  ```json
  {
    "stockRequestId": "5a3e07fd-158b-4d77-9b97-51844197ed9c",
    "status": "DISPATCHED"
  }
  ```

#### `GET /api/v1/movements`
Returns historical dispatch movements.
- **Gateway Mapping:** Translates to wire command `GET_MOVEMENTS`.
- **Response (200 OK):**
  ```json
  [
    {
      "id": 1,
      "stockRequestId": "5a3e07fd-158b-4d77-9b97-51844197ed9c",
      "sku": "ITEM-007",
      "quantityDelta": -8,
      "reason": "DISPATCH",
      "occurredAt": "2026-11-18T09:45:00Z"
    }
  ]
  ```

---

### 2.4 External Supplier Advisory Endpoint

#### `GET /api/v1/suppliers/availability`
Advisory supplier stock inquiry for replenishing unavailable items.
- **Gateway Mapping:** Translates to wire command `GET_SUPPLIER_STOCK`. The central server executes an outbound Java `HttpURLConnection` to the mock supplier service.
- **Query Parameters:** `sku` (required, e.g. `ITEM-008`).
- **Response — Available (200 OK):**
  ```json
  {
    "sku": "ITEM-008",
    "status": "AVAILABLE",
    "supplier": "Demo Supplier",
    "reportedQuantity": 20,
    "source": "MOCK_SUPPLIER",
    "checkedAt": "2026-11-18T10:00:00Z"
  }
  ```
- **Response — Known Zero Units (200 OK):**
  ```json
  {
    "sku": "ITEM-008",
    "status": "AVAILABLE",
    "supplier": "Demo Supplier",
    "reportedQuantity": 0,
    "source": "MOCK_SUPPLIER",
    "checkedAt": "2026-11-18T10:00:00Z"
  }
  ```
- **Response — Supplier Offline (503 Service Unavailable):**
  ```json
  {
    "code": "SUPPLIER_UNAVAILABLE",
    "message": "External supplier service is currently unreachable",
    "sku": "ITEM-008"
  }
  ```
- **Rule:** Supplier lookups are read-only and advisory. They never mutate central warehouse stock or trigger automatic purchasing.

---

## 3. HTTP to TCP Error Code Mapping

| Scenario | Protocol v1 Error Code | HTTP Status Code | Client Behavior |
| :--- | :--- | :--- | :--- |
| Reservation accepted | None | `201 Created` | Display success; wait for fulfillment. |
| Insufficient available stock | `INSUFFICIENT_STOCK` | `409 Conflict` | Display non-blocking error; offer supplier inquiry. |
| Replay of identical request | None | Same outcome | Return original recorded outcome idempotently. |
| Replay with conflicting body | `DUPLICATE_ID_CONFLICT`| `409 Conflict` | Alert operator of duplicate ID clash; request new UUID. |
| Invalid payload / malformed JSON | `VALIDATION_ERROR` | `400 Bad Request` | Display field validation errors on UI form. |
| SKU or Request ID not found | `NOT_FOUND` | `404 Not Found` | Show not-found notification. |
| Illegal lifecycle transition | `INVALID_TRANSITION` | `409 Conflict` | Reject invalid action (e.g. dispatch before pick). |
| Warehouse server unreachable | None (Socket disconnect)| `503 Service Unavailable` | Show reconnecting indicator; retry with same key. |
| Warehouse socket read timeout | None (Socket timeout) | `504 Gateway Timeout` | Inform operator outcome uncertain; safe to retry. |
| Internal server error | `INTERNAL_ERROR` | `500 Internal Error` | Log error; display safe error without stack traces. |
