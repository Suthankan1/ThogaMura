# Network protocol v1 — **proposed contract; freeze in Week 1**

Transport is hand-written Java `java.net` TCP upgraded to validated TLS for business commands. Spring Boot HTTP is a separate browser bridge, **not** this wire protocol. Member 1 and Member 5 own the command transport together.

## Framing and encoding

- Exactly one UTF-8 JSON object followed by a newline (`\n`) per frame.
- Message maximum **65,536 UTF-8 bytes** before newline. Reject larger or malformed frames and safely close as appropriate.
- A TCP read is **not** a complete message; data can arrive split or combined. Handle partial frame accumulation and multiple frames per read.
- Correlate every response via `requestId`; UUID v4 string chosen once per logical operation.
- Validate `v: 1` and `type`. Unknown versions/types receive explicit error result.
- Command data never enters UDP or the unauthenticated NIO signal channel.
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

`requestId` always identifies the **network command**; for state-transition commands `stockRequestId` identifies the original business request. Mutating calls may be retried with the same command requestId. Never reuse the same requestId for a different payload.

Error codes to implement: `VALIDATION_ERROR`, `NOT_FOUND`, `INSUFFICIENT_STOCK`, `INVALID_TRANSITION`, `DUPLICATE_ID_CONFLICT`, `UNSUPPORTED_VERSION`, `INTERNAL_ERROR`. Do not expose stack traces to clients.

## Durability and idempotency

For every mutating command, the server persists unique command requestId, canonical payload fingerprint and original response in `command_receipts` in the same transaction as the stock/state change. A replay of identical requestId/payload returns that recorded result, including after restart; the same ID with different command type/payload returns `DUPLICATE_ID_CONFLICT`. The original `stock_requests` row is separate business state. Transaction races must handle concurrent duplicate receipts safely. Exact receipt implementation is Member 4's responsibility.

## UDP discovery (Member 2)

Datagram to port 7445 (broadcast or known local addresses), proposed UTF-8 packet:

```json
{"v":1,"type":"DISCOVER","nonce":"client-generated-random-identifier"}
```

Server response echoes nonce:

```json
{"v":1,"type":"HERE","nonce":"client-generated-random-identifier","service":"ThogaMura","commandPort":7443,"notificationPort":7444}
```

Receiver trusts neither this packet nor claimed address. Server IP is determined from the datagram **source address**; TLS server identity must still be validated. Bound packet size (e.g. 1 KiB), retries/timeouts, manual-host fallback.

## Java NIO change feed (Member 3)

A separate `ServerSocketChannel` / `Selector` multiplexes gateway subscribers on port 7444. It sends at most a minimal line such as:

```json
{"v":1,"type":"CHANGED","version":42}
```

No SKU, request content, tokens or operator identities. Version increases after committed stock/request changes, but subscribers may miss messages. Feed is **best-effort invalidation**, not event storage. Handle partial writes via per-channel buffers and `OP_WRITE` only while data is queued. If backlog exceeds a bound, close slow subscribers; gateway reconnects and refetches authoritative status over TLS.

## TLS (Member 5)

`SSLServerSocket` and `SSLSocket` protect port 7443. Verify server certificate and hostname; optionally require gateway client certificate (proposed for demo) to block untrusted clients. Use per-developer keystores/truststores outside Git. No trust-all managers, hardcoded keys or silent plaintext fallback. TLS protects the **gateway-to-network-server** hop; the browser-to-gateway local HTTP hop is documented separately and requires HTTPS for non-local use.

## Protocol change process

One owner proposes change in `docs/decisions/DECISION_LOG.md`, updates both protocol/HTTP docs and examples, informs all five owners, adds compatibility tests. Do not silently let multiple AIs evolve incompatible JSON envelopes.
