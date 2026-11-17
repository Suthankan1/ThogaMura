# Network and correctness test plan — v2

## Levels
1. Unit: framing, canonical request IDs, validation, concurrency logic, HTTP response mapping.
2. Integration: **real** distinct JVMs for TCP/TLS/NIO; real separate supplier HTTP stub; real PostgreSQL for transactions.
3. Frontend: clear authoritative/unknown status, keyboard support, stale data and reconnect warning.
4. LAN rehearsal: browser/gateway on branch machine, central server/Postgres on warehouse machine; disconnect or firewall negative scenarios.

| ID | Test | Owner | Observable result |
| --- | --- | --- | --- |
| T01 | Split one TCP message into multiple writes | M1 | One decoded request |
| T02 | Two TCP messages in one write | M1 | Two separate correlated results |
| T03 | Drop response after central commit, retry same UUID | M1+M4 | Original persisted response; no second reservation |
| T04 | Supplier inquiry reaches independent HTTP service | M2 | Actual HTTP response + mapped advisory SKU availability |
| T05 | Supplier returns 404/503, times out or sends invalid JSON | M2 | Explicit UNKNOWN/UNAVAILABLE; no stock mutation or fabricated zero |
| T06 | NIO 10+ branch-like subscribers | M3 | One selector loop delivers commit version to connected clients |
| T07 | NIO partial write + slow/disconnected client | M3 | Bounded queues, `OP_WRITE`, no busy loop/leaks |
| T08 | Two parallel requests for 8 of 10 | M4 | At most one accepted; invariants hold |
| T09 | Same UUID, different payload | M4 | Conflict, no mutation |
| T10 | Dispatch same request twice | M4 | Exactly one movement, quantities correct |
| T11 | Restart central server and replay a UUID | M4 | Same durable response; stock unchanged |
| T12 | Trusted TLS certificate/hostname | M5 | Real handshake + command succeeds |
| T13 | Untrusted or mismatched TLS server identity | M5 | Fails closed, no plaintext fallback |
| T14 | Browser loses NIO/SSE hint then reconnects | M3+shared | Visible stale state and authoritative refetch |
| T15 | Frontend keyboard and invalid quantity | Shared | Accessible errors, no unsafe mutation |
| T16 | Branch-to-warehouse request -> picked -> dispatched | Everyone | Correct DB state after refresh/restart |
| T17 | Change supplier URL to unapproved host / invalid SKU | M2 | Rejected/validated, no SSRF or unbounded response |

### Correctness model
`available = on_hand - reserved >= 0`. Accepting 8 from 10 means `on_hand=10,reserved=8,available=2`; picking has no stock effect; dispatch yields `on_hand=2,reserved=0` and exactly one movement. Distinguish transaction integrity from thread scheduling.

### Safety
NIO feeds only version numbers, and only within a firewall-restricted **private demo LAN**. Supplier response is advisory and the mock connector never auto-orders. Use synthetic products `ITEM-007` (10 units), `ITEM-008` (0 units). No personal data, real keys or credentials in reports.

### Evidence
Keep true commands/results for all 5 individual features under `reports/evidence/`. A scenario is not green until actually executed.
