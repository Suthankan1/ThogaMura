# Project context — authoritative product brief

## Problem

Small shops and branch terminals ask a central warehouse for stock through chat, phone calls or paper. Simultaneous requests can exceed inventory; staff may not know whether a request submitted during a network interruption was accepted. Warehouse dispatch changes may not reach all branches promptly. When the warehouse lacks stock, staff need a truthful supplier availability inquiry.

## Proposed product

**ThogaMura** is a **LAN-first branch-to-central-warehouse stock request and fulfillment system**. A shop operator submits a request, the central warehouse atomically reserves or rejects it, warehouse staff pick/dispatch it, and connected branch gateways receive non-sensitive live change hints. For an unavailable item, an optional supplier HTTP connector provides read-only availability for manual follow-up.

## Primary users

- **Shop/branch operator:** checks stock, creates/retries requests, sees status, checks supplier availability when warehouse stock is insufficient.
- **Warehouse picker:** views accepted requests, picks and dispatches.
- **Supervisor/demo operator:** reviews balances, command outcomes, and movement history.

These roles are proposed; the MVP does not yet implement production human authorization.

## MVP success criteria

1. Branch UI/gateway and warehouse network server run on distinct devices/JVMs; all stock mutations cross real Java `java.net` sockets.
2. Request gets one persistent UUID and an ACCEPTED/REJECTED decision; duplicate identical retry replays original decision.
3. Concurrent requests cannot over-allocate stock; physical/reserved/available balances stay correct.
4. PICKED and DISPATCHED transitions persist one movement and survive server restart.
5. Java NIO notifies multiple branch gateways; browser refetches authoritative state after events or reconnect.
6. Real Java `HttpURLConnection` contacts a separately running supplier stub, reports valid result or explicit unavailable/unknown.
7. TLS handshake validates server identity and rejects a wrong/untrusted certificate.
8. Each of five members demonstrates an individually authored lecture concept with a negative network case.

## Out of scope

Full ERP, purchasing/payment, actual commercial supplier credentials/integration, automated reorder, accounting, invoicing, multiple warehouse ownership, offline mutation queues, hardware, mobile apps, AI and public internet deployment.

## Assumptions

- Single central warehouse + PostgreSQL; at least one shop/branch-side gateway.
- Core demo uses a private LAN; a disconnected browser cannot mutate central stock until reconnected.
- A static configured hostname/IP replaces unnecessary UDP discovery.
- Standard browser communicates HTTP/SSE; only Java services use raw TCP/NIO.
- One SKU per stock request in MVP.
- No customer validation yet. Validate the workflow with a shop operator before claiming business impact.

## Design authority

See `NETWORK_FEATURES_V2.md`, `ARCHITECTURE.md`, `TEAM_WORKLOADS.md`, and the two versioned contracts. Old PDF and UDP discovery tickets are historical; v2 is the active design.
