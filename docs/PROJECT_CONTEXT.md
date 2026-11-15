# Project context — authoritative product brief

## Problem

Small shop and warehouse teams often pass stock requests via calls, chat or paper. Two people may unknowingly request the same remaining stock; messages may be lost; staff cannot tell which request was picked or dispatched; internet connectivity may be unreliable.

## Proposed product

**ThogaMura** is a small LAN-first stock request and warehouse fulfilment desktop/web application. A shop operator views product availability and requests quantity from the warehouse. A server atomically accepts and reserves stock or rejects the request. A warehouse operator marks it picked and dispatched. The system persists all transitions and announces changes to connected clients.

## Primary users

- **Shop operator:** checks stock, creates and views own requests.
- **Warehouse picker:** sees accepted requests; picks and dispatches.
- **Supervisor/demo operator:** views stock and movement history.

User roles are **proposed**, not evidence of actual customer validation.

## MVP success criteria

1. Two computers on one local network operate through the web UI and gateway against one Java network server.
2. A stock request gets a unique requestId and an accepted/rejected result.
3. Competing reservations cannot oversubscribe stock.
4. Retrying an already processed requestId does not reserve twice.
5. Requests move to PICKED and DISPATCHED with a database movement record.
6. Browser sees a change indicator, refetches and shows authoritative state.
7. All five members demonstrate their assigned Java lecture concept and a failure case.
8. Server restart preserves stock and request state.

## Not in MVP

Full ERP, suppliers, purchasing, accounting, invoicing, payments, online cloud sync, mobile app, barcode hardware, AI/ML, complex RBAC, multi-warehouse, payment integration, background offline mutation queues.

## Assumptions and constraints

- A single warehouse and single locally hosted authoritative PostgreSQL instance.
- The local network may work without the internet, but Wi-Fi/router/server and electrical power must remain available.
- A browser speaks HTTP/SSE only; Java `java.net` and `java.nio` are exercised in separate server-side JVMs.
- Max one stock-keeping unit (SKU) per request for the first MVP. Multi-line orders are deferred.
- No actual shop validation has been performed; confirm with a real user before claiming impact.

## Name

Provisional title: **ThogaMura** (Sinhala-inspired combination of wholesale/stock goods and watch/duty). Group should check spelling and consent before public branding.
