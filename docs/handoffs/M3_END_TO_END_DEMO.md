# Member 3 — End-to-End Network Verification and Lecturer Demonstration Guide

- **Developer & Role:** Suthankan Balenthiran (@Suthankan1) — Member 3 (M3): Java NIO and Real-Time Notifications.
- **Repository:** https://github.com/Suthankan1/ThogaMura
- **Branch / PR:** `test/m3-notification-integration-demo` targeting `main`.
- **Primary Evidence File:** [reports/evidence/m3_e2e_integration_evidence.txt](../../reports/evidence/m3_e2e_integration_evidence.txt)
- **Status:** Fully verified end-to-end across multiple real running processes and automated test suites.

---

## 1. Executive Summary & Verified Pipeline Architecture

This document proves that the Java NIO notification architecture works across **independent, live OS processes** without mocks or artificial shortcuts.

```text
Process 1: Central Warehouse Server (Java NIO)
   │  ServerSocketChannel bound to 127.0.0.1:7444 (Loopback Security)
   │  Selector event loop: 'nio-notification-eventloop'
   │  ByteBuffer streaming, OP_WRITE registration, client session memory bounds
   ▼ [Raw TCP Unencrypted Invalidation Feed: {"v":1,"type":"CHANGED","version":<n>}\n]
Process 2: API Gateway (Spring Boot / Java Sockets)
   │  GatewayNioSubscriber (SocketChannel + NotificationFrameDecoder)
   │  Exponential backoff reconnection lifecycle (500ms -> 1000ms -> 2000ms -> 4000ms)
   │  SseNotificationBroadcaster (Asynchronous emitter pool)
   ▼ [HTTP Server-Sent Events: text/event-stream at GET /api/v1/events]
Process 3: Browser Client / Next.js
   │  StockEventsContext (Single EventSource singleton)
   │  ConnectionIndicator (Live / Reconnecting / Warehouse Paused / Offline)
   │  ActivityStatusClient (/activity 3-hop topology dashboard & event log)
   ▼ [Authoritative Invalidation Contract: data:<n> triggers REST refetch]
Authoritative REST API: GET /api/v1/products (PostgreSQL source of truth)
```

---

## 2. Test Count Audit & Discrepancy Analysis

### Investigation of PR #39 vs. PR #40 Test Counts
The user prompt highlighted a discrepancy: PR #39 reported 78 backend tests, while PR #40 reported 70 backend tests.

- **Root Cause Identified:** 
  - In `backend/network-server`, there are 8 distinct test classes.
  - One test class, `NioPrivateNetworkSecurityTest`, contains exactly **8 tests**.
  - During PR #40, an incremental Maven execution output was summarized as 27 tests in `network-server` (35 - 8 = 27) due to output truncation. No tests were deleted or altered.
- **Current Verified Count (clean verify):**
  - `backend/protocol`: 0 tests (pure interfaces/constants).
  - `backend/network-server`: **36 tests** (35 previous + 1 new Spring context autowiring test).
  - `backend/api-gateway`: **43 tests**.
  - **Total Backend Tests:** **79 tests** (all passing, 0 failures, 0 skipped).
  - **Frontend Unit Tests:** **7 tests** (`frontend/tests/stockEvents.test.ts`, all passing).
  - **Grand Total:** **86 automated tests** verified across the project.

---

## 3. Real-World Bug Discovered & Resolved

During the multi-process integration audit, starting `network-server` as a real Spring Boot process with `--thogamura.notifications.enabled=true` revealed a genuine runtime bug:

- **Error Encountered:**
  ```text
  org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'notificationServerLifecycle': 
  Failed to instantiate [NotificationServerLifecycle]: No default constructor found
  ```
- **Root Cause:**
  `NotificationServerLifecycle` had two constructors (a 6-argument `@Value` constructor and a 4-argument convenience constructor). Because neither constructor was annotated with `@Autowired`, Spring Boot's reflection mechanism could not resolve which constructor to invoke and attempted to look for a non-existent no-arg constructor `<init>()`. Unit tests had bypassed this by invoking `new NotificationServerLifecycle(...)` directly in code.
- **Fix Implemented:**
  1. Added `@Autowired` to the primary `@Value` constructor in `NotificationServerLifecycle.java`.
  2. Added an automated regression test in `NotificationServerLifecycleTest` using `ApplicationContextRunner` to prevent future regressions.
  3. Added an interactive `public static void main(String[] args)` launcher to `NioNotificationServer` and configured `exec-maven-plugin` in `backend/network-server/pom.xml`. This enables starting the pure Java NIO server directly via `mvn -f backend/network-server/pom.xml exec:java` without requiring an active PostgreSQL database connection.

---

## 4. End-to-End Multi-Process Verification Evidence

The full raw execution log is archived in [reports/evidence/m3_e2e_integration_evidence.txt](../../reports/evidence/m3_e2e_integration_evidence.txt). Key milestones verified:

| Step | Action | Actual Observed Result | Verification Status |
| :--- | :--- | :--- | :--- |
| **1. Server Start** | `mvn -f backend/network-server/pom.xml exec:java` | Starts non-blocking selector loop on `127.0.0.1:7444`, logs loopback restriction. | **PASS** |
| **2. Gateway Connect** | `mvn -f backend/api-gateway/pom.xml spring-boot:run` | Connects via `SocketChannel`, server logs `Accepted NIO client connection, active subscribers: 1`. | **PASS** |
| **3. Health Check** | `curl -s http://127.0.0.1:8080/api/v1/network-status` | `"nioFeed":{"status":"CONNECTED","connected":true,"notificationCount":0}`. | **PASS** |
| **4. SSE Stream** | `curl -N -s -H "Accept: text/event-stream" http://127.0.0.1:8080/api/v1/events` | SSE connection established; `activeClients` increments to 1. | **PASS** |
| **5. Controlled Event** | Input `42` into server console stdin | Server broadcasts `{"v":1,"type":"CHANGED","version":42}\n`; client receives `event:stock-changed\ndata:42`. | **PASS** |
| **6. Deliberate Failure** | Kill NIO server process | Gateway detects dropped socket, enters exponential backoff (`retrying in 500ms... 4000ms`). Status endpoint reports `"status":"RECONNECTING"`, `"connected":false`. Browser displays `Warehouse Paused`. | **PASS** |
| **7. Server Recovery** | Restart NIO server process | Gateway automatically reconnects; status returns to `"CONNECTED"`, `reconnectCount: 6`. | **PASS** |
| **8. Subsequent Event** | Input `43` into server console stdin | Client immediately receives `event:stock-changed\ndata:43`. | **PASS** |
| **9. Multi-Client SSE** | Connect second `curl` client | `"sse":{"activeClients":2}`. Both clients receive simultaneous events. | **PASS** |
| **10. Concurrency (T06)** | Run `NioMultiClientConcurrencyTest` | 12 real TCP clients serviced concurrently by **1 single selector loop thread** (`nio-notification-eventloop`). | **PASS** |
| **11. Slow Client (T07)** | Run `NioSlowSubscriberResilienceTest` | Stalled client queue overflows (max 5); slow client is evicted while healthy clients receive all 20 messages. | **PASS** |

---

## 5. Business Integration Boundary with Member 4 (M4)

### Current State
- `NotificationPublisher` interface and `NotificationServerLifecycle` component are implemented and ready in `backend/network-server`.
- **Member 4's transactional reservation logic (Issue #10) and dispatch movements (Issue #21) are not yet implemented.** The packages `lk.thogamura.networkserver.inventory`, `concurrent`, and `persistence` currently contain only `package-info.java`.
- **No notifications are published before transactions commit.**

### Exact Integration Contract for Member 4
When Member 4 implements the reservation service:
1. Inject the Spring bean `Optional<NotificationPublisher>` into the transaction service.
2. In the transactional service, register a Spring `TransactionSynchronization` callback or execute publication strictly **after** transaction commit:
   ```java
   @Transactional
   public ReservationResult reserveStock(ReservationRequest request) {
       // 1. Validate available stock in single DB transaction
       // 2. Insert reservation record & update on_hand - reserved
       // 3. Register post-commit notification trigger:
       TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
           @Override
           public void afterCommit() {
               notificationPublisher.ifPresent(p -> p.publish(nextChangeVersion));
           }
       });
       return ReservationResult.accepted(...);
   }
   ```
3. Never call `publish()` before the commit completes; uncommitted dirty reads must never trigger client cache invalidations.

---

## 6. Lecturer Demonstration Guide

This step-by-step procedure is designed for live presentation to university examiners.

### Prerequisites
- Java JDK 21+ (`java -version`)
- Maven 3.9+ (`mvn -version`)
- Node.js 22+ (`node -v`)

### Step-by-Step Execution

#### Terminal 1: Start Warehouse Java NIO Server
```bash
mvn -f backend/network-server/pom.xml exec:java
```
*Examiner Explanation:* Point out that this process starts a pure JDK `ServerSocketChannel` in non-blocking mode on `127.0.0.1:7444`. Notice the allowed client filter restricting connections to private loopback/LAN addresses.

#### Terminal 2: Start API Gateway
```bash
mvn -f backend/api-gateway/pom.xml spring-boot:run -Dspring-boot.run.arguments="--thogamura.notifications.subscriber.enabled=true"
```
*Examiner Explanation:* Point out in Terminal 1 that the NIO server immediately logs:
`Accepted NIO client connection from /127.0.0.1:<port>, active subscribers: 1`.
The gateway has connected via a non-blocking `SocketChannel` and is ready to forward version signals.

#### Terminal 3: Start Next.js Frontend
```bash
npm --prefix frontend run dev
```
Open browser to `http://localhost:3000/activity`.
*Examiner Explanation:* Show the **Live** badge (green). Point out the 3 diagnostic cards:
- Hop 1: Browser SSE Stream -> Connected (`/api/v1/events`).
- Hop 2: Warehouse NIO Feed -> Connected (`127.0.0.1:7444`).
- Hop 3: TLS Command Link -> Operational.

#### Step 4: Publish a Controlled Notification (Happy Path)
In **Terminal 1**, type `42` and press `Enter`.
- Terminal 1 prints: `Published change version: 42 (sent to 1 active subscriber(s))`.
- The browser `/activity` table immediately adds an entry: `Version: 42 | Type: CHANGED`.
- *Key Academic Point:* Highlight to the examiner that the SSE data payload is strictly `42`. It is an **invalidation signal**, not inventory data. The client responds by refetching authoritative product records from the database via REST.

#### Step 5: Simulate Network Disruption (Failure Case)
In **Terminal 1**, press `Ctrl+C` to kill the NIO server.
- Watch Terminal 2: The gateway logs `Connection refused. Retrying in 500ms... 1000ms... 2000ms...` showing exponential backoff.
- Look at the browser: Within 15 seconds, the status indicator shifts to **Warehouse Paused** (amber/yellow) and displays: *"Warehouse real-time feed is paused. On-screen quantities may be delayed."*
- *Examiner Explanation:* Explain that the browser's SSE link to the gateway is still open, but the gateway's upstream link to the warehouse is broken. The architecture prevents misleading operators into believing stale data is fresh.

#### Step 6: Simulate Recovery (Reconnection)
In **Terminal 1**, restart the server:
```bash
mvn -f backend/network-server/pom.xml exec:java
```
- In Terminal 2, the gateway automatically reconnects: `Connected to warehouse NIO notification feed at 127.0.0.1:7444`.
- In the browser, the indicator immediately switches back to **Live** (green) and automatically triggers an authoritative REST reconciliation.
- In Terminal 1, type `43` and press `Enter`. The browser immediately displays `Version: 43`.

#### Step 7: Safe Shutdown
Press `Ctrl+C` in Terminal 3, Terminal 2, and Terminal 1.

---

## 7. Java NIO Technical Concepts & Examiner Q&A Guide

When presenting Member 3's contribution, explain these explicit JDK APIs:

1. **`java.nio.channels.ServerSocketChannel`:**
   - Opened in non-blocking mode (`configureBlocking(false)`).
   - Binds to `127.0.0.1:7444`.
   - Registered with the selector for `SelectionKey.OP_ACCEPT`.
2. **`java.nio.channels.Selector`:**
   - Single selector event loop running on thread `nio-notification-eventloop`.
   - Multiplexes accept, read, and write readiness across all client channels in a single thread using OS epoll / kqueue primitives.
   - Eliminates the resource exhaustion of standard blocking thread-per-client architectures.
3. **`java.nio.channels.SocketChannel`:**
   - Configured non-blocking for each accepted subscriber.
   - Handled via `SelectionKey.OP_READ` (monitoring disconnections) and `OP_WRITE` (flushing pending outbound buffers).
4. **`java.nio.channels.SelectionKey` & `OP_WRITE` Lifecycle:**
   - Direct writes are attempted first. If the TCP send buffer fills (partial write), remaining bytes stay queued in `ClientSession`.
   - `SelectionKey.OP_WRITE` is registered only when buffered bytes remain (`key.interestOps(key.interestOps() | SelectionKey.OP_WRITE)`).
   - Once the socket becomes writable, `handleWrite()` drains the buffer and **immediately clears** `OP_WRITE`. This prevents CPU busy-spin polling when there is nothing to write.
5. **`java.nio.ByteBuffer` Streaming:**
   - Outbound JSON frames (`{"v":1,"type":"CHANGED","version":<n>}\n`) are serialized into byte buffers and streamed cleanly across chunk boundaries without frame fragmentation or data corruption.
6. **Slow Subscriber Memory Bounding:**
   - Each client session has a bounded outbound queue (default 50 messages).
   - If a slow client fails to read and exceeds this bound, the server closes its channel and evicts it, isolating slow subscribers so healthy subscribers never suffer latency or memory exhaustion.

The signed-in inventory now compares consecutive confirmed balances. Review the two-session browser rehearsal in frontend/tests/workspace-stock-demo.mjs and the setup instructions under Operator dashboard rehearsal. Feature implementation was AI-assisted and awaits individual source review and physical LAN demonstration.
