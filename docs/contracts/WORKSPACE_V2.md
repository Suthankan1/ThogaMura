# Authenticated branch workspace

The group agreed to ADR-008–010 on 23 November 2026. Both JVMs opt in with `THOGAMURA_ACCESS_ENABLED=true`. The demonstration profile continues to use [Protocol v1](NETWORK_PROTOCOL_V1.md). In workspace mode the warehouse accepts v1 `PING` and refuses v1 business commands. The gateway blocks v1 business HTTP routes. Diagnostics and version-only events remain available within the demo perimeter.

## Transport and identity

The Next.js app proxies `/api/v2/*` to its own gateway. The gateway verifies the locally provisioned account and issues an HttpOnly, SameSite=Strict session cookie. A session expires after 30 minutes of inactivity or eight hours from sign-in. The browser requests its CSRF token with the session details. Business mutations and sign-out require `X-Workspace-CSRF`. Login requires JSON and `X-Workspace-Client: browser`; the gateway does not enable cross-origin access to these routes.

The gateway sends newline-delimited UTF-8 JSON over the same hostname-validated JSSE transport, using `v: 2`, the command name in `type`, and a lowercase UUID v4 `requestId`. The `data` object contains `args` and an `access` assertion with `name`, `role`, `branch`, `expires` and `signature`. The signature is Base64URL HMAC-SHA256 over the UTF-8 newline-separated sequence `THOGAMURA_ACCESS_V2`, command type, request UUID, name, role, branch, expiry in epoch seconds, and the compact JSON serialization of `args` in wire field order. The gateway signs for 30 seconds. The warehouse validates the signature and expiry before executing a business command. The shared key belongs to the gateway and warehouse service processes. NIO continues to carry version-only invalidation hints.

| Capability | Branch operator | Warehouse picker | Warehouse supervisor |
| --- | --- | --- | --- |
| Product and supplier advisory reads | Yes | Yes | Yes |
| Request list and exact lookup | Own branch | All workspace branches | All workspace branches |
| Reserve stock | Own branch | — | — |
| Cancel an accepted reservation | Own branch | All workspace branches | All workspace branches |
| Pick and dispatch | — | All workspace branches | All workspace branches |
| Receive stock and read movements | — | — | Yes |

The warehouse assigns the branch from the signed assertion. Callers receive 404 for absent or out-of-scope request references. Legacy records have no assigned branch and remain in the demonstration profile. Allocate a fresh database or complete a verified ownership mapping before onboarding real branch records. The application uses a trusted gateway perimeter with a shared signing key and locally provisioned passwords.

## HTTP and warehouse commands

| HTTP under `/api/v2` | Warehouse command | Inputs |
| --- | --- | --- |
| `GET /products` | `GET_PRODUCTS` | Optional SKU prefix `search` |
| `GET /requests` | `GET_REQUESTS` | Optional exact `status` |
| `GET /requests/{id}` | `GET_REQUEST` | `stockRequestId` from path |
| `POST /requests` | `REQUEST_STOCK` | `sku`, positive integer `quantity` |
| `POST /requests/{id}/cancel` | `CANCEL_REQUEST` | `stockRequestId` from path |
| `POST /requests/{id}/pick` | `MARK_PICKED` | `stockRequestId` from path |
| `POST /requests/{id}/dispatch` | `MARK_DISPATCHED` | `stockRequestId` from path |
| `POST /receiving` | `RECEIVE_STOCK` | Existing `sku`, positive integer `quantity`, nonblank `note` up to 160 characters |
| `GET /movements` | `GET_MOVEMENTS` | Supervisor ledger |
| `GET /suppliers/availability?sku=…` | `GET_SUPPLIER_STOCK` | Advisory lookup by `sku` |
| `GET /session`, `POST /session`, `DELETE /session` | Gateway-local session | Sign-in JSON contains `name` and `password` |

List responses contain `items` and `nextCursor`. Pass the cursor unchanged to the same list and filter. Limits range from 1 to 24 and default to 16. The sort keys are SKU ascending, request creation timestamp and UUID descending, and movement ID descending. Each cursor marks an exclusive boundary. A page traversal reads live records, so status changes can affect a filtered traversal between requests. Refresh the first page to see new records ahead of the current cursor. Product names and decision reasons are bounded display fields.

A mutating request requires an `Idempotency-Key` header with a lowercase UUID v4. Repeat the original request with the same key until the outcome can be confirmed. Successful stock and receiving writes return 201. Successful transitions return 200. Domain failures use 400, 401, 403, 404 or 409. Dependency failures can return 502, 503 or 504. The warehouse uses the normal versioned `RESULT` envelope, including `FORBIDDEN` for invalid assertions or insufficient privileges.

Cancellation changes `ACCEPTED` to `CANCELLED` and releases reserved units in the same transaction. Receiving adds to on-hand units, preserves reservations, and records one positive `RECEIVE` ledger entry with the actor, note and command receipt. Dispatch records one negative movement per request. Stock and ledger changes commit with their receipts. A picked or dispatched request cannot be cancelled by this workflow.

[Deployment and test setup](../SETUP.md#authenticated-branch-workspace) · [Approved decisions](../decisions/DECISION_LOG.md#adr-008-bounded-warehouse-reads-and-request-lookup)
