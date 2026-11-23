# Operator dashboard rehearsal, 23 November 2026

The recordings show two independent authenticated Chromium sessions on loopback. Each used its own Next.js instance and Spring gateway. Both gateways subscribed to the private Java NIO listener and sent signed commands over TLS to the separate warehouse JVM. PostgreSQL and the supplier simulator used disposable synthetic fixtures.

- [North branch recording](branch-north.webm) and [south branch recording](branch-south.webm) show the two reservations. The first draft kept seven while the confirmed availability fell from eight to six. The operator corrected it to six and submitted. A later withheld request for four leeks reached the warehouse after a different branch reserved one. The warehouse rejected it.
- [Live balance](live-balance.png) shows the updated inventory row, draft and quantity warning together.
- [Desktop overview](overview-desktop.png) and [mobile overview](overview-mobile.png) show Red Onions and the waiting Organic Carrots request. Chromium checked that both actions fit inside 1280 × 720 and 390 × 844 viewports.
- The picker checked Red Onions with the independent HTTP simulator, created a follow-up and closed it. Chromium dropped the creation acknowledgement after the warehouse committed. After reload the picker retried the saved command with its original key and received the existing task. The other session opened its read-only record from inventory and observed the closure.
- In a separate fault-injection run, a temporary loopback relay disconnected one gateway’s NIO subscription while the other reserved a potato. The disconnected browser retained its last confirmed balance. After reconnect it showed four reserved, previously three, and kept the entered quantity of two.

The API rehearsal also checked concurrent creation, conflict replay after closure, unknown owners, invalid dates, cross-branch request isolation and timestamps through dispatch. See the [reproduction instructions](../../../docs/SETUP.md#operator-dashboard-rehearsal). Physical two-device LAN rehearsal and individual coursework review remain pending.

Verification included the Maven reactor, disposable PostgreSQL migration tests, 49 frontend unit tests, ESLint, TypeScript, the webpack production build, and both demo and authenticated Chromium regressions. The default Turbopack production compiler hit a local worker socket permission error in this environment.
