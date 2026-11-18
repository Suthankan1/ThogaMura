# ThogaMura interactive frontend prototype

[Open the standalone HTML demo](ThogaMura_Interactive_Prototype.html) or download that file and double-click it in a modern browser. It has no build, server, npm, network, or database dependency. The HTML contains its own CSS, JavaScript, and sample data.

## Walkthrough

1. **Products** — search products and request a quantity of available stock.
2. **Request tracking** — review the resulting accepted or rejected request.
3. **Warehouse queue** — mark an accepted request `PICKED`, then `DISPATCHED`. The demo updates stock and movement history.
4. **Suppliers** — compare available, confirmed zero, unknown SKU, and timeout responses.
5. **Activity & network** — toggle simulated transport states, simulate a stock arrival, or drop the next command response.
6. **Recovery** — retry the preserved request ID. The simulated receipt returns the original result without reserving stock a second time.

On narrow screens, use the four-item bottom navigation and **More** to reach the secondary views. Use **Reset sample data** in Activity or More to restore the starting state. Local demo state persists in browser storage where available.

## Important boundaries

- This is an **interactive visual prototype**, not production Next.js source or a running integration.
- All network behavior (TCP/TLS, NIO notifications, supplier HTTP, timeouts, retries) is **simulated in browser JavaScript**. No real socket, HTTP service, or PostgreSQL transaction is exercised.
- Local state and sample stock figures are for demonstration only. They must **not** be used as evidence that any of the five members' Java networking features work.
- The implementation source of truth remains `frontend/` and the documented contracts. A real frontend must use the Spring Boot gateway, not this demo's browser-local state.
- Do not expose the Java NIO notification port to the public internet. The real feed carries version-only change hints; authoritative inventory is refetched over the command path.

The editable design reference is the [Figma prototype](https://www.figma.com/design/cmuF9UH2IFmVngU4wrDcsz).
