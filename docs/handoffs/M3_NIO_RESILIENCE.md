# Handoff — Member 3 (Suthankan Balenthiran) / Java NIO Resilience & Recovery (Issue #20)

- **Developer & Role:** Suthankan Balenthiran (@Suthankan1) — Member 3 (M3): Java NIO and Real-Time Notifications.
- **Branch / PR:** Branch `test/m3-nio-resilience-20`, targeting `main`. Reference: `Refs #20`.
- **Target Issue:** [Issue #20: [M3][W3] NIO partial writes, slow clients and reconnect tests](https://github.com/Suthankan1/ThogaMura/issues/20).
- **Core Purpose:** Demonstrates and validates the resilience of the non-blocking Java NIO selector notification architecture under real failure modes, partial socket writes, slow/stalled subscribers, and reconnection recovery boundaries without artificial mocks.

---

## 1. Verified Acceptance Criteria & Test Plan Coverage

| Test ID | Test Description | Owner | Test Class | Observable Result |
| :--- | :--- | :--- | :--- | :--- |
| **T06** | NIO 10+ branch-like subscribers | M3 | `NioMultiClientConcurrencyTest` | Single selector event loop (`nio-notification-eventloop`) concurrently services 12 real TCP clients without thread-per-client model. All 12 receive strict sequential change versions without corruption. |
| **T07** | NIO partial write + slow/disconnected client | M3 | `NioPartialWriteResilienceTest`<br>`NioSlowSubscriberResilienceTest` | Partial buffer writes remain queued; `SelectionKey.OP_WRITE` registered to resume transmission; `OP_WRITE` cleared upon completion to prevent CPU busy-spin. Slow client queue overflow evicts stalled client while healthy clients receive all notifications. |
| **T14** | Browser/Gateway loses NIO/SSE hint then reconnects | M3+shared | `NioReconnectionResilienceTest` | Subscriber detects disconnection, enters `RECONNECTING`, and reconnects with exponential backoff. Missed events during downtime trigger authoritative REST reconciliation boundary. |

---

## 2. Java NIO Concepts & JDK APIs Demonstrated

1. **`java.nio.channels.Selector` & Multiplexed Event Loop:**
   - Server multiplexes all client `SocketChannel` instances on a single selector loop thread (`nio-notification-eventloop`).
   - Verified that scaling from 1 to 12 simultaneous subscribers does **not** create worker threads per client.
2. **`java.nio.channels.SelectionKey` & `OP_WRITE` Lifecycle:**
   - Outbound data is written immediately; if the OS socket send buffer fills (partial write), `session.flush()` returns `false`.
   - Remaining bytes stay buffered at the head of `ClientSession.outboundQueue`.
   - The selector thread registers `SelectionKey.OP_WRITE` (`key.interestOps(key.interestOps() | SelectionKey.OP_WRITE)`).
   - Once the socket becomes writable, `handleWrite()` drains the buffer and immediately clears `OP_WRITE` (`key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE)`).
   - **Busy-spin prevention:** Verified with `selector.selectNow()` that no polling loop occurs when queues are empty.
3. **`java.nio.ByteBuffer` Streaming & Framing Integrity:**
   - Byte buffers are sliced across multiple partial writes (e.g. 7-byte and 10-byte fragments) without dropping or duplicating bytes.
   - Wire JSON (`{"v":1,"type":"CHANGED","version":<n>}\n`) is reconstructed bit-for-bit identically.
4. **Slow Subscriber Protection & Memory Bounding:**
   - Per-client queues are strictly bounded (`maxPendingMessagesPerClient`, default 20).
   - When a slow client fails to drain its socket, excess messages are rejected (`session.enqueue(...) == false`).
   - The server disconnects the slow subscriber, cancels its `SelectionKey`, and closes the channel, freeing memory via `session.clear()`.
   - **Failure isolation:** Healthy concurrent subscribers continue receiving events with zero message loss.
5. **Abrupt Disconnection Fault Tolerance (TCP RST):**
   - Verified that client sockets closed abruptly with `setSoLinger(true, 0)` (TCP RST) are detected and cleaned up cleanly without crashing the selector loop.

---

## 3. T14 Reconnection & Authoritative Recovery Boundary

Because the warehouse NIO notification feed is a lightweight, non-durable real-time cache invalidation channel (transmitting only versions `{"v":1,"type":"CHANGED","version":<n>}` and no business data):
- The NIO server **does not** retain messages for disconnected clients.
- When a branch gateway subscriber reconnects after network downtime or server restart (`reconnectCount > 0`), it knows that intermediate invalidation events may have been dropped.
- **Architectural Recovery Contract:**
  - Gateway transitions from `RECONNECTING` to `CONNECTED`.
  - Next.js frontend / branch client receives SSE reconnection or status notification.
  - Client must perform an **authoritative REST fetch** (`GET /api/v1/warehouse/inventory` or `GET /api/v1/products`) rather than assuming it saw every incremental change.

---

## 4. Test Execution Summary

Executed across root reactor:
```bash
mvn -f backend/pom.xml test
```

- **Total Tests Executed:** 70
- **Failures:** 0
- **Errors:** 0
- **Skipped:** 0
- **Module Breakdown:**
  - `protocol`: 0 tests (pure interfaces/constants)
  - `network-server`: 27 tests (all passing)
  - `api-gateway`: 43 tests (all passing)
- **Evidence Record:** `reports/evidence/m3_nio_resilience.txt`

---

## 5. Live Demonstration Procedure for Examiners

To demonstrate Java NIO resilience live to an evaluator:

1. **Start Warehouse Network Server:**
   ```bash
   mvn -f backend/network-server/pom.xml exec:java
   ```
2. **Start API Gateway:**
   ```bash
   mvn -f backend/api-gateway/pom.xml spring-boot:run
   ```
3. **Verify Connected NIO Feed:**
   ```bash
   curl -s http://127.0.0.1:8080/api/v1/network-status | jq .
   ```
   *Expected:* `"warehouseFeed":{"status":"CONNECTED","reconnectCount":0}`.
4. **Kill and Restart Warehouse Server:**
   - Stop the `network-server` process.
   - Run `curl -s http://127.0.0.1:8080/api/v1/network-status | jq .` -> Status transitions to `"RECONNECTING"`.
   - Restart `network-server`.
   - Run `curl -s http://127.0.0.1:8080/api/v1/network-status | jq .` -> Status returns to `"CONNECTED"`, `"reconnectCount": 1`.
5. **Run Automated Resilience Suite:**
   ```bash
   mvn -f backend/pom.xml test -Dtest=NioPartialWriteResilienceTest,NioSlowSubscriberResilienceTest,NioMultiClientConcurrencyTest,NioReconnectionResilienceTest
   ```
