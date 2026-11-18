# Handoff — Member 3 (Suthankan Balenthiran) / Gateway SSE Bridge (Issue #19B)

- **Owner and lecture concept:** Member 3 (@Suthankan1) — Real-time notification bridge translating client-side Java NIO notifications (`SocketChannel`) into HTTP Server-Sent Events (`text/event-stream` via Spring Boot `SseEmitter`).
- **Branch / commit / PR:** Branch `feat/m3-gateway-sse-19b`, targeting `main`. Pull request created for review (`Refs #19`).
- **One-sentence behavior:** Spring Boot API gateway SSE bridge streaming `stock-changed` version-only invalidation events to multiple simultaneous HTTP/browser clients upon receiving Java NIO notifications from the central warehouse server.
- **Changed files:**
  - `backend/api-gateway/src/main/java/lk/thogamura/gateway/sse/SseNotificationBroadcaster.java`
  - `backend/api-gateway/src/main/java/lk/thogamura/gateway/sse/package-info.java`
  - `backend/api-gateway/src/main/java/lk/thogamura/gateway/web/EventsController.java`
  - `backend/api-gateway/src/main/java/lk/thogamura/gateway/web/NetworkStatusController.java`
  - `backend/api-gateway/src/main/resources/application.yml`
  - `backend/api-gateway/src/test/java/lk/thogamura/gateway/sse/SseNotificationBroadcasterTest.java`
  - `backend/api-gateway/src/test/java/lk/thogamura/gateway/web/EventsControllerTest.java`
  - `backend/api-gateway/src/test/java/lk/thogamura/gateway/web/NetworkStatusControllerTest.java`
  - `backend/api-gateway/src/test/java/lk/thogamura/gateway/integration/NioToSseIntegrationTest.java`
  - `docs/handoffs/M3_GATEWAY_SSE.md`
- **Contract changes:** None. Conforms strictly to `docs/contracts/HTTP_API_V1.md` (`GET /api/v1/events` and `GET /api/v1/network-status`) and `docs/contracts/NETWORK_PROTOCOL_V1.md`.
- **Architecture and data flow:**
  ```text
  Warehouse NIO Notification Server (127.0.0.1:7444)
                |
                | TCP — Java NIO SocketChannel
                v
  GatewayNioSubscriber (lk.thogamura.gateway)
                |
                | NotificationListener callback (onNotification)
                v
  SseNotificationBroadcaster (Asynchronous Bounded Executor)
                |
                | HTTP text/event-stream
                v
  GET /api/v1/events (Spring MVC SseEmitter)
                |
                v
  Multiple connected SSE clients (Browsers / HTTP clients)
  ```
- **SSE event format:**
  ```text
  event:stock-changed
  data:42

  ```
  The payload carries strictly the version number. No SKU, product name, quantities, customer details, or credentials are transmitted over SSE.
- **Slow-client and failure isolation:**
  - **Non-blocking NIO reading thread:** Broadcasting runs asynchronously on a dedicated executor (`sse-broadcaster`), guaranteeing that slow or stalled SSE clients never block the Java NIO selector/subscriber reading loop.
  - **Failure isolation:** If any connected client drops or times out (`IOException`), that specific emitter is cleaned up immediately without impacting delivery to other subscribers.
  - **Bounded capacity:** Caps maximum concurrent SSE connections (default 100, configurable via `thogamura.notifications.sse.max-emitters`) returning HTTP 503 when full. Bounded internal dispatch queue (default 256) drops older invalidations under severe saturation.
  - **Graceful shutdown:** Completes active emitters and terminates worker threads cleanly upon Spring ApplicationContext shutdown.
- **Network status endpoint:**
  - `GET /api/v1/network-status` reports central warehouse host, NIO subscriber feed status (`CONNECTED`, `CONNECTING`, `RECONNECTING`, etc.), reconnect counts, and active SSE client metrics.
- **Commands actually run:**
  - `mvn -f backend/pom.xml clean verify`
  - Cross-platform verification inside Linux Docker container (`maven:3.9-eclipse-temurin-21`)
  - `git diff --check`
- **Observed result and test count:** 62 tests executed across the backend reactor, 0 failures, 0 errors, 0 skipped (41 in `api-gateway`, 21 in `network-server`).
- **Network demo actually observed:**
  - Real loopback TCP socket server sending `{"v":1,"type":"CHANGED","version":501}\n`.
  - Gateway NIO subscriber parses frame and invokes `NotificationListener`.
  - Broadcaster dispatches asynchronously to real HTTP client connected to `GET /api/v1/events`.
  - HTTP client receives `event:stock-changed` with `data:501`.
  - Malformed frame `{"v":1,"type":"INVALID","version":999}\n` is safely rejected and never broadcast to HTTP clients.
- **Security limitations:**
  - The SSE feed carries only version invalidation signals, never authoritative stock data.
  - Private LAN only: The warehouse NIO feed remains restricted to loopback/private network.
  - Authoritative inventory reconciliation must be performed over authenticated REST endpoints (`GET /api/v1/products`).
- **What is implemented and what remains pending:**
  - **Implemented (Issue #19A & #19B):** Java NIO subscriber, frame decoder, exponential backoff reconnect, Spring Boot SSE broadcaster, `GET /api/v1/events`, `GET /api/v1/network-status`.
  - **Pending:** Next.js frontend invalidation hook connecting to `GET /api/v1/events` and refreshing REST cache on event/reconnect.
