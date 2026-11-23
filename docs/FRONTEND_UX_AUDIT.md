# ThogaMura frontend UI and UX audit

**Reviewed:** 23 November 2026\
**Baseline:** `12f4e3e`\
**Coverage:** All eight frontend routes across the demo and authenticated profiles. Browser checks used Chromium with synthetic warehouse data at desktop, tablet, and phone widths, including short landscape.

The authenticated workspace makes operators do more manual work than the demo screens. Its product selection, shortage recovery, and supplier results have fewer contextual actions. The highest-priority defects are overlapping mobile filters, misleading diagnostics, and an unvalidated receiving confirmation.

## Fix first

| Issue | Finding and correction |
| --- | --- |
| **Mobile search controls overlap** | At 375 px, the search box overlaps its filter by **113 px** on Inventory and **149 px** on Requests. Both pages fit the viewport, so a document-width check misses the defect. Give search a full row on narrow screens and test the controls for overlap. [Inventory](../frontend/src/app/products/ProductsClient.tsx#L42) · [Requests](../frontend/src/app/requests/RequestsClient.tsx#L73) |
| **Receiving can show false success** | An intercepted HTTP `201` response containing an empty JSON object displayed **“Receiving confirmed”** and cleared the saved action. Validate the receipt before reporting success. Preserve the original command when the outcome remains uncertain. [Workspace command handling](../frontend/src/app/workspace/WorkspaceClient.tsx#L137) |
| **Protected diagnostics reports a healthy read path as failed** | Diagnostics calls the demo inventory endpoint, which the authenticated gateway blocks. It showed **“Read failed”** while authenticated inventory worked. **“Run a live inquiry”** also opened Inventory instead of Suppliers. Use the authenticated read and give the supplier section a direct destination. [Diagnostics](../frontend/src/app/activity/ActivityStatusClient.tsx#L18) · [Profile redirects](../frontend/next.config.ts#L26) |
| **Short landscape navigation clips links** | At 740 × 360, the bottom of the Branch Workspace link was at 433 px. The drawer has no internal scroll container, leaving lower links out of reach by touch. Constrain the drawer to the viewport and make its navigation scrollable. [Navigation drawer](../frontend/src/components/Header.tsx#L92) |
| **Mobile tables hide operational information** | At 375 px, authenticated Requests and Movements needed about 400 px inside a 309 px container. Requests hid the Created column and wrapped item codes and status labels awkwardly. Inventory scrolled horizontally at 320 px. Consider labeled cards on narrow screens, or supply a visible scroll cue and an accessible way to reach all columns. [Workspace table](../frontend/src/app/workspace/WorkspaceClient.tsx#L353) |
| **Reservation drafts and navigation context disappear** | After entering a SKU and quantity, switching to Requests and back cleared both fields. Reloading Movements returned to Inventory. Preserve unfinished drafts and restore the selected section, filter, and list position across navigation. [Workspace state](../frontend/src/app/workspace/WorkspaceClient.tsx#L106) |
| **Demo history can hide recent requests** | The warehouse returns the oldest 500 legacy requests and the browser sorts that subset newest-first. Search and detail lookup only inspect loaded rows. A recent request can exist while the page reports that its reference was absent from the loaded history. Use authoritative pagination and exact lookup where the operator workflow requires them. [Warehouse query](../backend/network-server/src/main/java/lk/thogamura/networkserver/persistence/JdbcStockRequestRepository.java#L113) · [Request history](../frontend/src/app/requests/RequestsClient.tsx#L18) |

## Workflow and interaction gaps

| Area | Gaps |
| --- | --- |
| **Role-based navigation** | Operators, pickers, and supervisors all land on Inventory. A picker must open Requests, choose a status, and drill into a record before acting. A dedicated queue could show what to pick or dispatch, how long it has waited, and the next action. |
| **Protected inventory** | Search accepts a case-sensitive SKU prefix. Product-name search and availability filters are missing. Rows have no Reserve or Check Supplier action, and zero stock looks similar to a healthy balance. The reservation form sits below the catalogue on phones and tablets, where users must scroll and re-enter the SKU. |
| **Reservation and receiving forms** | Both ask for a bare SKU without a searchable product picker, item summary, current balance, or contextual field errors. Chromium accepted `1e3` as a valid entry and interpreted it as `1,000`. Receiving offers no review of the item and quantity before changing on-hand stock. |
| **Shortage recovery** | The protected workflow displays an error without offering to adjust quantity, query the supplier for the same item, or return to a preserved draft. The demo reservation screen already supports much of this flow. |
| **Request discovery** | Protected Requests has a status filter and exact lookup by a 36-character reference. Search by product, partial reference, branch, or date is missing. Filters expose values such as `ACCEPTED` while badges say **“To pick.”** |
| **Request detail** | The detail shows SKU, quantity, branch, status, and creation time. It lacks the product name, last transition time, visible lifecycle, and a copy-reference action. Returning to the list loses filters and position. Opening cancellation leaves focus on the original button and does not announce the newly revealed confirmation. |
| **Uncertain outcomes** | **“Retry saved action”** preserves the command key but hides the affected SKU, quantity, request reference, and command ID. An unresolved action disables other writes with little explanation. Protected requests also lack a browser-side deadline and can wait without a clear recovery step. |
| **Movement ledger** | The protected supervisor ledger paginates but lacks SKU, movement-type, and date filters, search, and export. Dispatch rows show a branch without linking to the request. Receipt rows show the actor and note but omit the command reference. In the demo, **“View stock movements”** opens the full ledger rather than the related movement. Demo date filters use UTC while displayed timestamps use local time. |
| **Supplier inquiries** | Protected results omit the supplier name, data source, checked time, and comparison with warehouse stock. Operators re-enter the SKU when moving from inventory to an inquiry. Pressing **Check supplier** again for the same SKU does not send a new lookup. The separate **Refresh supplier** button does. |
| **Sign-in and session recovery** | Failed sign-in clears both the account name and password. The error is announced, but focus drops back to the page. There is no password visibility toggle. Reauthentication preserves an exact request link when present, while other section and draft state is lost. |
| **Demo operations overview** | Headline counts represent loaded records, with the scope shown in small text. Pending work has no prominent age or priority cue, and the overview surfaces out-of-stock items without surfacing low-stock items. The demo menu also links to a workspace that is usually disabled in this profile. |
| **Demo list scale** | Inventory, Requests, and Warehouse render long lists with limited list controls. Applying Inventory or Requests filters reloads the page. The warehouse queue has no SKU or reference search within a stage. On narrow rows, action buttons squeeze or truncate request references and dates. |

[Workspace screens](../frontend/src/app/workspace/WorkspaceClient.tsx#L177) · [Demo reservation form](../frontend/src/app/requests/new/NewRequestClient.tsx) · [Demo warehouse queue](../frontend/src/app/warehouse/WarehouseQueueClient.tsx) · [Demo overview](../frontend/src/app/page.tsx)

## Visual design and accessibility gaps

- **The screens lack distinct task hierarchies.** The same white panels, gray dividers, and similarly weighted headings carry inventory, receiving, diagnostics, and request detail. In protected Inventory, available stock has the same visual weight as on-hand and reserved stock. The ledger distinguishes receipts and dispatches mainly by text and a sign. Give each screen's key number, state, and next action a clear visual priority.
- **The design system splits across profiles.** The demo uses responsive inventory rows, item-level actions, and a dispatch dialog. The authenticated profile uses generic tables, detached forms, and inline confirmations. Share field, table, status, and confirmation patterns so users learn them once.
- **Primary and destructive actions look too similar.** The same filled teal button selects a navigation section, submits stock, and confirms cancellation. On small screens, identity and Sign out sit above work controls. Clarify action priority and explain the consequences of stock changes in confirmation controls.
- **Touch targets and contrast need work.** Several row and diagnostics links measured **17–20 px high**, including the protected Open request action. The compact connection indicator conveys its visual state through a colored dot. The small coursework footer text measured **2.51:1** against its background.
- **Loading, empty, and stale states are too generic.** Lists often present only a small freshness line or **“No matching records.”** Empty queues, unmatched filters, and connection failures need different messages and recovery actions. Make stale inventory visible inside protected work screens.
- **Coursework language appears in daily tasks.** The reservation form exposes **“Evaluator Demo Scenarios & Idempotency Key,”** and the header can show a raw version number. Status labels vary across forms, filters, and badges. Keep technical details available in Diagnostics and use consistent operational language in the work screens.
- **Recovery and wayfinding are thin.** The unknown-page screen uses the generic Next.js 404. Exact request detail is shareable, but most protected sections and selected records cannot be linked directly. A person sent to Receiving, Movements, or an individual supplier SKU has to reconstruct that context.

[Shared UI patterns](../frontend/src/components/Workspace.tsx) · [Connection indicator](../frontend/src/components/ConnectionIndicator.tsx#L49) · [Page shell and footer](../frontend/src/app/layout.tsx#L14)

## Verification limits

The review included stock and supplier flows on disposable warehouse data, keyboard navigation, narrow and short viewports, and a simulated malformed receiving reply. Safari, Firefox, and screen-reader behavior remain unverified.
