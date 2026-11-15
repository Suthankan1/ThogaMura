# Backend and persistence plan

## Modules

- `backend/protocol`: protocol version constant and shared transport-neutral types; only add a DTO here if both JVMs require it.
- `backend/network-server`: Spring Boot lifecycle, custom `java.net`/`java.nio` socket services, inventory application service, Spring JDBC transactions, Flyway.
- `backend/api-gateway`: Spring MVC REST and SSE, validated DTOs, Java TLS client, Java NIO feed subscriber, UDP discovery client, configurable manual fallback. **No PostgreSQL dependency**.

Spring Boot is a lifecycle/DI/transaction tool; assessed socket operations must be directly implemented with JDK networking APIs and visible in source.

## Package ownership proposal

| Area | Package | Owner |
| --- | --- | --- |
| TCP command framing, mapping, retries | `networkserver.transport.tcp` and `gateway.network.tcp` | Member 1 |
| Discovery | `networkserver.discovery` and `gateway.network.discovery` | Member 2 |
| NIO feed | `networkserver.notifications` and `gateway.network.notifications` | Member 3 |
| Atomic reservation and worker pool | `networkserver.inventory`, `networkserver.persistence`, `networkserver.concurrent` | Member 4 |
| TLS configuration, certificate verification and authorization gates | `networkserver.security` and `gateway.network.security` | Member 5 |

Package paths are sketches; actual Java package prefix is `lk.thogamura`.

## Schema (starter migration)

1. `product_stock`: SKU, name, on_hand, reserved; check constraints protect non-negative values and `reserved <= on_hand`.
2. `stock_requests`: UUID request_id, SKU, quantity, status, timestamps. `request_id` is a durable uniqueness/receipt key.
3. `stock_movements`: append-only dispatch movement, unique per request.
4. `command_receipts`: durable original result for each mutating command requestId, including state transitions.

Single SKU per stock request. Seed test data only in explicit development setup.

## Reservation transaction

1. Begin transaction.
2. Check if command requestId has a **durable processed receipt**. If it does, return the original stored outcome (including rejected results), even after restart. Conflicting body under the same ID is rejected.
3. Lock stock row (`SELECT ... FOR UPDATE`) or use a conditional update guaranteeing `on_hand - reserved >= qty`.
4. If enough stock, increment reserved and persist accepted request; otherwise persist rejected decision; persist corresponding `command_receipts` result atomically.
5. Commit; only then publish NIO invalidation.

**Important:** the initial schema has a status field for accepted/rejected, but the team must explicitly confirm the stored response shape and canonical replay behavior before implementing the reservation service.

## Dispatch transaction

For one `PICKED` request: decrement on_hand and reserved together, transition to `DISPATCHED` and insert one movement row in the same transaction, together with command receipt. Duplicate dispatch with the same command ID replays original response and yields no second movement. Different command IDs are still guarded by state transition/unique movement.

## Threading model

Command accept loop -> bounded `ExecutorService` -> request handler -> transaction. Graceful stop closes sockets, stops accepting, allows in-flight tasks to finish where safe, releases workers. Distinguish application concurrency correctness (DB transaction) from Java thread scheduling.

## Operational endpoints

Gateway: `GET /actuator/health` shows that the gateway JVM is alive, **not** that the network server works; add explicit `/api/v1/network-status` when implementing network checks.

Network server: no browser HTTP endpoint; Java listeners are started as application lifecycle components when their owners implement them.
