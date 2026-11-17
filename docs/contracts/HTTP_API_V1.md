# HTTP API v1 — Spring Boot gateway contract

**Planned endpoints only.** The starter gateway does not implement these business endpoints yet. Base URL during local dev: `http://localhost:8080/api/v1`. Browser never connects to PostgreSQL or the custom TCP server directly.

## Endpoints

| Method | Route | Behavior | Network server command |
| --- | --- | --- | --- |
| GET | `/products` | List products / availability | GET_PRODUCTS |
| POST | `/requests` | Reserve one SKU | REQUEST_STOCK |
| GET | `/requests` | List requests | GET_REQUESTS |
| POST | `/requests/{id}/pick` | ACCEPTED -> PICKED | MARK_PICKED |
| POST | `/requests/{id}/dispatch` | PICKED -> DISPATCHED | MARK_DISPATCHED |
| GET | `/movements` | Dispatch history | GET_MOVEMENTS |
| GET | `/events` | SSE `stock-changed` signal | NIO subscription |
| GET | `/network-status` | Configured central host, TLS command and NIO feed status | TLS/NIO checks |
| GET | `/suppliers/availability?sku=...` | Advisory external supplier stock inquiry; never change central stock | GET_SUPPLIER_STOCK (central server uses Java URL/HTTP) |

The only implemented starter route is `GET /api/v1/meta` and it explicitly reports `"scaffold": true`.

## Sample request

```http
POST /api/v1/requests
Content-Type: application/json
Idempotency-Key: 5a3e07fd-158b-4d77-9b97-51844197ed9c

{"sku":"ITEM-007","quantity":8}
```

```json
{"requestId":"5a3e07fd-158b-4d77-9b97-51844197ed9c","status":"ACCEPTED","sku":"ITEM-007","quantity":8}
```

The HTTP idempotency key is translated unchanged into TCP `requestId`. On ambiguous HTTP/socket timeout, the client may retry the **same** key. A new user action gets a new UUID. Server validation is authoritative; frontend validation is UX.

## HTTP mapping proposal

| Case | HTTP code |
| --- | --- |
| Created request with accepted reservation | 201 |
| Valid request rejected for insufficient stock | 409 |
| Duplicate identical idempotency key | Same outcome as original |
| Duplicate key with conflicting body | 409 |
| Malformed input | 400 |
| Unknown SKU or stock request | 404 |
| Invalid state transition | 409 |
| Network server unavailable | 503 |
| Network server response timed out, outcome uncertain | 504 with same-key retry advice |

Do **not** return `201` on a mock or optimistic result. Include an error code and safe display message. Do not leak stack traces or credentials.

## Supplier availability mapping

Supplier lookup is **read-only and optional**; the central Java network server contacts a separately running HTTP supplier mock for demos (or an authorized configured supplier API in a real integration). Return a result with `sku`, `supplier`, `reportedQuantity` (only if known), and `source`/`checkedAt` when available. For 404/503, timeout or malformed response, return a distinct UNKNOWN/UNAVAILABLE error and display a safe message. Never claim that unknown equals zero. This endpoint cannot accept an arbitrary supplier URL and cannot reserve warehouse stock. Example:

```json
{"sku":"ITEM-008","status":"AVAILABLE","supplier":"Demo Supplier","reportedQuantity":20,"source":"MOCK_SUPPLIER"}
```

## SSE stream

`GET /api/v1/events` emits a `stock-changed` event containing only a version number or empty data. Browser invalidates its cached products/requests and fetches via REST. Event delivery is not guaranteed. On disconnect, show a banner and refetch on reconnect.

## Security note

The first lab prototype uses the browser on localhost and may not have individual human accounts. **Do not present this as production-ready access control.** The trusted gateway-to-server hop uses TLS and optionally client certificates; before any real deployment add human login/authorization and protect browser-to-gateway traffic with HTTPS.
