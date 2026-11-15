# Planned SSE events

Subscribe to `GET /api/v1/events` on the Spring Boot gateway (not directly to Java NIO). An event is only a cache-invalidation signal; refetch via REST after each event, and on reconnect/focus. Show disconnected state; never display invented stock values. No implementation is present in this starter.
