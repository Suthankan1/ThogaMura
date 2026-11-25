# ThogaMura Live Application Screenshots (Report & Evidence)

Captured from a live local run of the `main` branch (`20c4c04`) with Next.js production mode (`THOGAMURA_FRONTEND_MODE=start`), JSSE TLS command transport (`THOGAMURA_TLS_ENABLED=true`), Java NIO notification feed, independent Supplier HTTP Simulator, Spring Boot API Gateway, and PostgreSQL seeded via `./start.sh seed`.

## Captured Screens

| File | Route / State | Viewport | Evidence Purpose |
| --- | --- | --- | --- |
| [01-warehouse-overview-desktop.png](01-warehouse-overview-desktop.png) | `/` | 1440x900 | Warehouse overview metrics, fulfillment queue, stock exceptions, live SSE status |
| [02-warehouse-overview-mobile.png](02-warehouse-overview-mobile.png) | `/` | 390x844 | Responsive mobile overview layout |
| [03-products-inventory-desktop.png](03-products-inventory-desktop.png) | `/products` | 1440x900 | Product catalog with on-hand, reserved, available counts and stock badges |
| [04-products-low-stock-filter.png](04-products-low-stock-filter.png) | `/products?filter=low_stock` | 1440x900 | Filtered low-stock inventory view |
| [05-new-request-form.png](05-new-request-form.png) | `/requests/new?sku=ITEM-007` | 1440x900 | Stock reservation form validated against server-confirmed availability |
| [06-m1-ambiguous-retry.png](06-m1-ambiguous-retry.png) | `/requests/new?sku=ITEM-007` | 1440x900 | M1 ambiguous response handling (`Request outcome unconfirmed` + `Retry same request` preserving UUID) |
| [07-reservation-confirmed.png](07-reservation-confirmed.png) | `/requests/new?sku=ITEM-007` | 1440x900 | Idempotent replay confirmation (`Stock Hold Confirmed`, `ACCEPTED` status, UUID v4 reference) |
| [08-stock-requests-list.png](08-stock-requests-list.png) | `/requests` | 1440x900 | All request lifecycle states (`To pick`, `Ready to dispatch`, `Dispatched`, `Declined`) |
| [09-request-detail-dialog.png](09-request-detail-dialog.png) | `/requests?ref=...` | 1440x900 | Request detail drawer with full UUID reference and creation timestamp |
| [10-warehouse-queue-pick.png](10-warehouse-queue-pick.png) | `/warehouse` | 1440x900 | Warehouse picker queue (`To pick` stage) |
| [11-warehouse-dispatch-modal.png](11-warehouse-dispatch-modal.png) | `/warehouse` | 1440x900 | Dispatch confirmation dialog (`Confirm dispatch`) after picking |
| [12-warehouse-dispatch-confirmed.png](12-warehouse-dispatch-confirmed.png) | `/warehouse` | 1440x900 | Confirmed dispatch state banner |
| [13-warehouse-movements-ledger.png](13-warehouse-movements-ledger.png) | `/warehouse` (Movements) | 1440x900 | M4 PostgreSQL stock movement audit ledger (`-2 units`, `-1 units`) |
| [14-supplier-availability-ok.png](14-supplier-availability-ok.png) | `/suppliers?sku=ITEM-008` | 1440x900 | M2 read-only supplier HTTP lookup (`20 units reported` from mock supplier service) |
| [15-supplier-unavailable.png](15-supplier-unavailable.png) | `/suppliers?sku=ITEM-OUTAGE` | 1440x900 | M2 supplier outage handling (`Supplier unavailable`) |
| [16-network-diagnostics-tls-nio.png](16-network-diagnostics-tls-nio.png) | `/activity` | 1440x900 | M3 NIO feed (`Connected · version hints only`, live `Version 1–3` hints), M5 TLS (`Verified TLS response`), and SSE stream (`Connected`) |
| [17-requests-mobile.png](17-requests-mobile.png) | `/requests` | 390x844 | Responsive mobile card list for stock requests |
| [18-warehouse-mobile.png](18-warehouse-mobile.png) | `/warehouse` | 390x844 | Responsive mobile fulfillment queue |

## Observed Gateway Status (`/api/v1/network-status`)

```json
{
  "centralHost": "127.0.0.1",
  "nioPort": 7444,
  "nioFeed": {
    "enabled": true,
    "status": "CONNECTED",
    "connected": true,
    "lastReceivedVersion": 3,
    "notificationCount": 3,
    "reconnectCount": 0
  },
  "sse": {
    "activeClients": 0,
    "broadcastCount": 3,
    "lastBroadcastVersion": 3
  },
  "tlsCommand": {
    "status": "OPERATIONAL",
    "owner": "Member 5"
  }
}
```
