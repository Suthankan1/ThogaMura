# Network protocol v1 — **proposed contract; freeze in Week 1**

Transport is hand-written Java `java.net` TCP upgraded to validated TLS for business commands. Spring Boot HTTP is a separate browser bridge, **not** this wire protocol. Member 1 and Member 5 own the command transport together.

## Framing and encoding

- Exactly one UTF-8 JSON object followed by a newline (`\n`) per frame.
- Message maximum **65,536 UTF-8 bytes** before newline. Reject larger or malformed frames and safely close as appropriate.
- A TCP read is **not** a complete message; data can arrive split or combined. Handle partial frame accumulation and multiple frames per read.
- Correlate every response via `requestId`; UUID v4 string chosen once per logical operation.
- Validate `v: 1` and `type`. Unknown versions/types receive explicit error result.
- Command data never enters the unauthenticated NIO signal channel.
- Socket timeout: propose 3s connection and 5s command read for local demo; revise by measurement and document retry policy.

## Envelope

Request:

```json
{"v":1,"type":"REQUEST_STOCK","requestId":"5a3e07fd-158b-4d77-9b97-51844197ed9c","data":{"sku":"ITEM-007","quantity":8}}
```

Response:

```json
{"v":1,"type":"RESULT","requestId":"5a3e07fd-158b-4d77-9b97-51844197ed9c","status":"ACCEPTED","data":{"sku":"ITEM-007","quantity":8}}
```

Rejected result:

```json
{"v":1,"type":"RESULT","requestId":"5a3e07fd-158b-4d77-9b97-51844197ed9c","status":"REJECTED","error":{"code":"INSUFFICIENT_STOCK","message":"Requested quantity exceeds available stock"}}
```

## Command set

| Type | Data | Result |
| --- | --- | --- |
| `GET_PRODUCTS` | optional filter / page | products with onHand, reserved, available |
| `REQUEST_STOCK` | SKU, positive quantity | ACCEPTED or REJECTED, same requestId |
| `GET_REQUESTS` | optional status | requests with states |
| `MARK_PICKED` | stockRequestId | PICKED or error |
| `MARK_DISPATCHED` | stockRequestId | DISPATCHED or error |
| `GET_MOVEMENTS` | optional page | immutable movements |
| `GET_SUPPLIER_STOCK` | SKU only | read-only advisory supplier availability or UNKNOWN/UNAVAILABLE |

`requestId` always identifies the **network command**; for state-transition commands `stockRequestId` identifies the original business request. Mutating calls may be retried with the same command requestId. Never reuse the same requestId for a different payload.

Error codes to implement: `VALIDATION_ERROR`, `NOT_FOUND`, `INSUFFICIENT_STOCK`, `INVALID_TRANSITION`, `DUPLICATE_ID_CONFLICT`, `UNSUPPORTED_VERSION`, `SUPPLIER_UNAVAILABLE`, `SUPPLIER_TIMEOUT`, `SUPPLIER_UNKNOWN`, `INTERNAL_ERROR`. Do not expose stack traces to clients.

## Durability and idempotency

For every mutating command, the server persists unique command requestId, canonical payload fingerprint and original response in `command_receipts` in the same transaction as the stock/state change. A replay of identical requestId/payload returns that recorded result, including after restart; the same ID with different command type/payload returns `DUPLICATE_ID_CONFLICT`. The original `stock_requests` row is separate business state. Transaction races must handle concurrent duplicate receipts safely. Exact receipt implementation is Member 4's responsibility.

## Supplier HTTP adapter (Member 2)

The mock contract is documented in `SUPPLIER_HTTP_V1.md`. The branch gateway sends a regular TLS/TCP `GET_SUPPLIER_STOCK` command to the central server. The **central Java server** then calls a configured external supplier endpoint using `java.net.URI`, `URL` and `HttpURLConnection`. This is a separate protocol from the custom TCP command framing.

Example command:

```json
{"v":1,"type":"GET_SUPPLIER_STOCK","requestId":"a11b5173-520e-4bb3-82a2-b6a4c3e02e9f","data":{"sku":"ITEM-008"}}
```

Example advisory response:

```json
{"v":1,"type":"RESULT","requestId":"a11b5173-520e-4bb3-82a2-b6a4c3e02e9f","status":"AVAILABLE","data":{"sku":"ITEM-008","supplier":"Demo Supplier","reportedQuantity":20,"source":"MOCK_SUPPLIER"}}
```

When supplier is down, respond with `status: "UNAVAILABLE"` and a safe error code; **do not** turn a timeout into `reportedQuantity:0`. This read-only operation does not mutate warehouse stock or place orders. Supplier URL must be configured by operator, not supplied in the command body. For the demo, use a separate mock supplier HTTP process; outside localhost, HTTPS and certificate validation are required.

## Java NIO change feed (Member 3)

A separate `ServerSocketChannel` / `Selector` multiplexes branch gateway subscribers on port 7444 on a **restricted private demo LAN**. This port must never be exposed on untrusted networks before an encrypted/authenticated transport or VPN is added. It sends at most a minimal line such as:

```json
{"v":1,"type":"CHANGED","version":42}
```

No SKU, request content, tokens or operator identities. Version increases after committed stock/request changes, but subscribers may miss messages. Feed is **best-effort invalidation**, not event storage. Handle partial writes via per-channel buffers and `OP_WRITE` only while data is queued. If backlog exceeds a bound, close slow subscribers; gateway reconnects and refetches authoritative status over TLS.

## TLS (Member 5)

`SSLServerSocket` and `SSLSocket` protect port 7443. Verify server certificate and hostname; optionally require gateway client certificate (proposed for demo) to block untrusted clients. Use per-developer keystores/truststores outside Git. No trust-all managers, hardcoded keys or silent plaintext fallback. TLS protects the **gateway-to-network-server** hop; the browser-to-gateway local HTTP hop is documented separately and requires HTTPS for non-local use.

## Protocol change process

One owner proposes change in `docs/decisions/DECISION_LOG.md`, updates both protocol/HTTP docs and examples, informs all five owners, adds compatibility tests. Do not silently let multiple AIs evolve incompatible JSON envelopes.
