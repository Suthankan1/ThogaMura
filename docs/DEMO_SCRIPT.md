# Live group demo (approximately 6–8 minutes)

## Before starting

Use two laptops or a laptop plus separate Java processes; preload synthetic products with known balances. Show PostgreSQL persists through server restart. Prepare one normal success and five member-specific failure scenarios. Do not use secret keystores in screenshots.

| Order | Presenter | Action | Learning proof |
| --- | --- | --- | --- |
| 1 | M2 | Gateway auto-finds server using UDP; manual fallback | DatagramSocket, timeout, address |
| 2 | M5 | Valid TLS socket succeeds; wrong trust fails | SSL socket, cert verification |
| 3 | M1 | Request 8 of 10, receive response; replay after uncertain response | TCP framing and requestId |
| 4 | M4 | Race two requests for 8; at most one reservation; dispatch once | Threads + transactions |
| 5 | M3 | Ten subscribers receive CHANGED; one disconnects; UI refetches | Selector + ByteBuffer |
| 6 | Everyone | Show browser warehouse queue -> PICKED -> DISPATCHED -> history | End-to-end integration |

**Narration rule:** Every student explains their own classes, key JDK APIs, input/output behavior, negative test and integration seam. Spring Boot and Next.js are supporting frameworks, not the subject of assessed feature presentations.

## Backup plan

If campus Wi-Fi blocks broadcast, use a private hotspot and manual IP fallback. Have saved logs/screenshots from previously verified tests but distinguish recordings from a live pass.
