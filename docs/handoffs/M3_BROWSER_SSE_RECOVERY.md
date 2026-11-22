# Handoff — Member 3 (Suthankan Balenthiran) / Browser SSE Subscription & Recovery Integration

- **Developer & Role:** Suthankan Balenthiran (@Suthankan1) — Member 3 (M3): Java NIO and Real-Time Notifications.
- **Branch / PR:** Branch `feat/m3-browser-sse-recovery`, targeting `main`. References: `Refs #19`, `Refs #13`.
- **Target Issue:** [Issue #19: [M3][W2] Java NIO subscriber in gateway, SSE bridge, and browser reconnect](https://github.com/Suthankan1/ThogaMura/issues/19).
- **Core Purpose:** Integrates the browser frontend (Next.js) with the Spring Boot gateway SSE endpoint (`GET /api/v1/events`), maintaining real-time awareness of warehouse inventory and request updates while strictly enforcing the architectural rule that invalidation events trigger authoritative REST queries and never supply raw inventory counts.

---

## 1. Verified Acceptance Criteria & Frontend Test Coverage

| Test ID / Requirement | Description | Component / Unit Test | Observable Result |
| :--- | :--- | :--- | :--- |
| **SSE Invalidation Ingestion** | Invalidation event `stock-changed` with `{"v":1,"type":"CHANGED","version":42}` | `StockEventsContext.tsx`<br>`tests/stockEvents.test.ts` (Req 1 & 2) | Dispatches invalidation callbacks to registered listeners; version `42` is logged as a monotonically increasing change counter and **never** interpreted as an inventory count. |
| **Disconnection Warning** | SSE connection loss or server downtime | `ConnectionIndicator.tsx`<br>`tests/stockEvents.test.ts` (Req 3) | Transitions connection status to `DISCONNECTED` / `RECONNECTING`. Displays an accessible amber banner alerting the operator that on-screen data may be stale. |
| **Reconnection Recovery** | SSE connection restoration after dropped link | `StockEventsContext.tsx`<br>`tests/stockEvents.test.ts` (Req 4) | Native `EventSource` reconnects; context automatically triggers a debounced authoritative refresh (`triggerRefresh()`) and clears the stale-data warning. |
| **Page Focus Recovery** | Tab switching or window minimization | `StockEventsContext.tsx`<br>`tests/stockEvents.test.ts` (Req 5) | `visibilitychange` and `focus` event handlers immediately trigger an authoritative REST sync to catch up on any notifications dropped while the tab was asleep. |
| **Upstream NIO Feed Distinction** | Gateway SSE is open, but warehouse Java NIO socket is broken | `StockEventsContext.tsx`<br>`tests/stockEvents.test.ts` (Req 6) | Context polls `GET /api/v1/network-status`. If `warehouseFeed.status !== "CONNECTED"`, UI marks `isLive = false` and displays "Warehouse Paused" rather than falsely reporting healthy end-to-end sync. |
| **Single Shared Connection** | Multiple components subscribe to notifications | `StockEventsContext.tsx`<br>`tests/stockEvents.test.ts` (Req 7) | A single browser `EventSource` instance services all components. Listener registry allows components to mount and unmount without triggering duplicate network connections. |
| **Rapid Notification Debounce** | High-frequency warehouse changes | `StockEventsContext.tsx`<br>`tests/stockEvents.test.ts` (Req 8) | Invalidation triggers are debounced (300ms window) to prevent concurrent REST storming when multiple change events fire rapidly. |

---

## 2. Architectural Design & Separation of Concerns

### End-to-End Notification Flow

```text
Central Warehouse (Process 1)
       │
       ▼ [Java java.nio.channels.Selector Event Loop]
Raw TCP Unencrypted Feed (:9091) — {"v":1,"type":"CHANGED","version":42}\n
       │
       ▼ [GatewayNioSubscriber SocketChannel + Line Frame Decoder]
Spring Boot API Gateway (Process 2)
       │
       ▼ [NotificationBroadcaster SseEmitter Registry]
HTTP Server-Sent Events (SSE) (:8080/api/v1/events)
       │
       ▼ [Next.js NextProxy / EventSource]
Browser Client (Process 3 - Next.js)
       │
       ├─► StockEventsContext (Single EventSource singleton + Listener Registry)
       ├─► ConnectionIndicator (Header status badge + Stale warning banner)
       ├─► ActivityStatusClient (/activity 3-hop diagnostics & audit log)
       └─► Member 2 Screens (/products, /requests via useStockEvents hook)
              │
              └─► REST refetch: GET /api/v1/products (Authoritative state)
```

### Key Implementation Principles

1. **Strict Version Invalidation vs. Data Derivation:**
   - Wire event payload: `data: {"v":1,"type":"CHANGED","version":42}`.
   - The browser treats `version` strictly as an invalidation notification counter.
   - Stock quantities, available balances, reservation states, and product records are **always** fetched authoritatively from the PostgreSQL-backed REST API (`GET /api/v1/products`).
2. **Distinct Failure Domain Visualization:**
   - **Client-to-Gateway SSE:** Native `EventSource` manages client reconnection.
   - **Gateway-to-Warehouse Java NIO Feed:** The browser queries `GET /api/v1/network-status` every 15 seconds (and immediately upon reconnection). If the warehouse NIO daemon is stopped, the browser header displays `Warehouse Paused` even if the local SSE stream is healthy.
3. **No Redundant Socket/SSE Sockets:**
   - `<StockEventsProvider>` is mounted once in `frontend/src/app/layout.tsx`.
   - Child components call `const { isLive, registerListener, triggerRefresh } = useStockEvents()`.
4. **Proxy Rewrites:**
   - `frontend/next.config.ts` includes proxy rewrites for `/api/v1/:path*` pointing to `${GATEWAY_URL || "http://localhost:8080"}/api/v1/:path*`, allowing cross-origin safety in local development without browser CORS friction.

---

## 3. Files Created & Modified

### Created Files
- `frontend/src/lib/types/network.ts`: TypeScript contracts for SSE connection state (`CONNECTING`, `CONNECTED`, `RECONNECTING`, `DISCONNECTED`), upstream NIO feed state, network status API responses, and invalidation event log entries.
- `frontend/src/lib/api/network.ts`: API client function `fetchNetworkStatus()` communicating with `GET /api/v1/network-status` with timeout and error fallback.
- `frontend/src/lib/events/StockEventsContext.tsx`: Root React Context managing the browser `EventSource` connection, listener registry, debounced refresh scheduling, tab visibility recovery, upstream feed polling, and stale banner alerts.
- `frontend/src/lib/events/useStockEvents.ts`: Clean hook exporting `useStockEvents()` and `<StockEventsProvider />`.
- `frontend/src/components/ConnectionIndicator.tsx`: Accessible top-navigation status pill (`role="status"`, `aria-live="polite"`) with distinct visual badges (Live, Reconnecting, Warehouse Paused, Offline), manual refresh button, and stale alert banner.
- `frontend/src/app/activity/ActivityStatusClient.tsx`: Interactive network observability dashboard with 3-hop diagnostic status cards, manual reconciliation trigger button, and live invalidation event log table.
- `frontend/tests/stockEvents.test.ts`: Automated test suite verifying invalidation dispatch, version decoupling, disconnect warnings, reconnect recovery, page focus recovery, upstream NIO pause handling, listener cleanup, and debounce timing.
- `docs/handoffs/M3_BROWSER_SSE_RECOVERY.md`: This comprehensive handoff document.

### Modified Files
- `frontend/src/app/layout.tsx`: Wrapped application tree in `<StockEventsProvider>` and placed `<ConnectionIndicator />` in the global header bar.
- `frontend/src/app/activity/page.tsx`: Updated to render `<ActivityStatusClient />` inside a React `Suspense` boundary.
- `frontend/next.config.ts`: Added `/api/v1/:path*` proxy rewrite to Spring Boot gateway (`http://localhost:8080`).
- `frontend/package.json`: Added `test` script running `node --experimental-strip-types --test tests/stockEvents.test.ts`.

---

## 4. Test Execution Summary

### Frontend Verification
```bash
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run build
```

- **Unit Tests:** 7 passed, 0 failed (duration 102ms).
- **TypeScript Typecheck:** 0 errors.
- **ESLint:** 0 warnings, 0 errors.
- **Production Next.js Build:** Compiled successfully in 1000ms; all 8 static routes prerendered.

### Backend Verification
```bash
mvn -f backend/pom.xml test
```
- **Total Tests Executed:** 70
- **Failures:** 0
- **Errors:** 0
- **Skipped:** 0

---

## 5. Live Demonstration Guide for Evaluators

Follow these steps to demonstrate real-time SSE recovery to an examiner:

### Setup
1. **Start the Warehouse Network Server (Terminal 1):**
   ```bash
   mvn -f backend/network-server/pom.xml exec:java
   ```
2. **Start the API Gateway (Terminal 2):**
   ```bash
   mvn -f backend/api-gateway/pom.xml spring-boot:run
   ```
3. **Start the Next.js Frontend (Terminal 3):**
   ```bash
   npm --prefix frontend run dev
   ```
4. **Open Browser:** Navigate to `http://localhost:3000/activity`.

### Scenarios to Demonstrate
1. **Healthy 3-Hop Live Stream:**
   - In the top header and on `/activity`, verify the badge displays `Live` with a green indicator.
   - The diagnostic cards confirm:
     - Hop 1: Browser SSE Stream -> Connected (`/api/v1/events`).
     - Hop 2: Warehouse NIO Feed -> Connected (`127.0.0.1:9091`).
     - Hop 3: TLS Inventory Command -> Operational.
2. **Real-Time Notification Invalidation:**
   - Trigger a stock change in the warehouse (or simulate a notification broadcast).
   - Observe the live invalidation counter tick up on `/activity` (`Event #42`, `Type: CHANGED`).
   - Notice that the table logs the event version, while the product inventory values are fetched from the database REST endpoint.
3. **Warehouse NIO Daemon Disruption (Partial Failure):**
   - Kill the warehouse `network-server` process in Terminal 1.
   - The gateway's `GatewayNioSubscriber` logs a reconnecting backoff.
   - Within 15 seconds, the browser indicator shifts to `Warehouse Paused` (yellow/amber warning).
   - An alert banner appears: *"Warehouse real-time feed is paused. On-screen quantities may be delayed."*
   - Restart `network-server` in Terminal 1.
   - The indicator automatically returns to `Live` (green) and triggers an authoritative refresh.
4. **Browser Tab Sleep & Focus Recovery:**
   - Switch to another browser tab or minimize the browser window for 15 seconds.
   - Return focus to the ThogaMura tab.
   - The status log records an automatic page focus recovery event, refreshing all authoritative tables.
5. **Gateway Down / SSE Drop:**
   - Stop the API Gateway in Terminal 2.
   - The browser status badge switches to `Reconnecting` (amber) with a warning banner.
   - Restart the API Gateway.
   - The browser's native `EventSource` automatically reconnects and re-synchronizes authoritative data.

---

## 6. Integration Notes for Team Members

- **Member 2 (Products & Requests UI — Issue #13):**
  - To hook into real-time invalidation in `src/app/products/page.tsx` or `src/app/requests/page.tsx`, simply import:
    ```tsx
    import { useStockEvents } from "@/lib/events/useStockEvents";
    ```
  - In your component, register your fetch function:
    ```tsx
    const { registerListener, isStale } = useStockEvents();
    useEffect(() => {
      return registerListener(() => {
        void refetchProductList();
      });
    }, [registerListener]);
    ```
  - You do **not** need to create another `EventSource` or parse JSON notifications.
- **Member 4 (TLS Commands & Transactions — Issue #10):**
  - When inventory reservation transactions commit on the warehouse server, publish a version increment to the `NioNotificationServer`. The notification will ripple through the gateway's NIO subscriber, Spring Boot's SSE broadcaster, and update the browser within milliseconds.

## Stabilization handoff

The provider owns one EventSource per tab and queues a follow-up authoritative fetch when a hint arrives during a read. The warehouse handoff is bounded and coalesces stale hint versions. Capture the physical multi-peer reconnect evidence during #27.

## Extended closeout verification

Each SSE client has a bounded writer and one pending hint. A blocked emitter cannot delay an active peer. The branch subscriber now sets socket connect and TCP keepalive bounds. On an isolated private bridge, the gateway detected quiet link loss after 16 seconds and reconnected after network reattachment. Bring the physical firewall and two-device evidence to the live rehearsal. [Recorded checks](../../reports/evidence/segmented_lan_closeout.txt)
