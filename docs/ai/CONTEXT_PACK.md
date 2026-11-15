# AI context pack — paste or reference at the beginning of any task

**Name:** ThogaMura — LAN-first stock request/warehouse fulfilment, Java Network Programming Assignment 02.

**Must demonstrate:** Five individually authored lecture concepts: M1 `java.net` TCP reliability/framing, M2 UDP discovery, M3 Java NIO selector change notifications, M4 multithreading + atomic PostgreSQL stock reservation, M5 JSSE TLS certificate validation.

**Stack:** Next.js App Router + TypeScript + Tailwind -> Spring Boot HTTP/SSE gateway -> **separate JVM** Spring Boot network server using Java `java.net`/`java.nio` -> PostgreSQL. Maven Java 21, npm/Node 22, Docker Compose.

**Key constraints:**

- HTTP does not replace the real socket call; gateway cannot call network server internals or DB directly.
- Command socket must be TLS protected in final demo. UDP and NIO do not carry confidential data.
- Atomic reserved stock; `available = on_hand - reserved >= 0`; requestId replay has same persisted outcome.
- Browser uses server truth, not optimistic inventory.
- No full ERP/accounting/cloud/AI scope.
- Tests + manual demo required for each member's contribution.
- This repository is an **initial scaffold**, not a completed working app.

**Read in order when relevant:** `AGENTS.md` -> `docs/TEAM_WORKLOADS.md` -> `docs/contracts/NETWORK_PROTOCOL_V1.md` / `HTTP_API_V1.md` -> `docs/TEST_PLAN.md` -> module code.

**Expected AI output:** a brief understanding, owner and slice, proposed file changes, code explanation, focused tests and limitations. Never claim tests or demo ran if not actually executed. No secrets or personal data in prompts.
