# Test strategy — proof of networking and correctness

## Test levels

1. **Unit:** codec framing, validation, message mapping, state transitions and retry control.
2. **Integration:** real sockets between two JVMs, PostgreSQL transactions, certificate handshakes and NIO selector delivery.
3. **UI:** form correctness and user-visible loading/error/uncertain states.
4. **LAN acceptance:** actual devices, router/hotspot, realistic disconnect and fallback.

## Required scenarios

| ID | Test | Owner | Expected evidence |
| --- | --- | --- | --- |
| T01 | TCP splits one JSON frame in several writes | M1 | One parsed command only |
| T02 | TCP sends multiple frames in one write | M1 | Correct separate results |
| T03 | Timeout after commit + same-ID retry | M1+M4 | One reservation, original result |
| T04 | UDP discovery + nonce check | M2 | Actual datagrams, matching address |
| T05 | Discovery blocked -> manual IP | M2 | Gateway connects without UDP |
| T06 | NIO 10+ clients + change event | M3 | One selector, all active clients signaled |
| T07 | Slow NIO subscriber | M3 | Backpressure bounded and disconnected |
| T08 | Two requests for 8 from 10 | M4 | One accepted, one rejected |
| T09 | Duplicate ID with different body | M4 | Conflict and no stock change |
| T10 | Dispatch same request twice | M4 | Exactly one movement |
| T11 | Restart network server and retry | M4 | Request receipt intact |
| T12 | Trusted TLS identity | M5 | Handshake succeeds |
| T13 | Untrusted/wrong TLS identity | M5 | Handshake fails closed |
| T14 | Browser SSE disconnect + reconnect | M3+shared | Visible warning and refetch |
| T15 | Keyboard-only stock request | Shared UI | Focus, label, error message, success |
| T16 | Complete two-machine lifecycle | Everyone | Request -> picked -> dispatched |

## Verify state, not only HTTP return codes

For oversubscription, assert DB values and request outcomes after test. For idempotency, assert **same requestId and same persisted decision**, no duplicate reserved count. For dispatch, assert `on_hand` and `reserved` both decrease and one movement exists. For NIO, assert real selectors/channels and correct behavior after disconnect. For TLS, inspect exception/handshake result and ensure plaintext cannot access command service.

## Test data

Use synthetic `ITEM-007` 10 units and `ITEM-008` 0 units. Never use actual shop information in coursework screenshots or test fixtures.

## Proof log

Keep short commands, test counts, screenshots (where appropriate), participant name and observed outcome under `reports/evidence/`. Never mark a scenario 'green' until it was executed. Record environment restrictions honestly.
