---
trigger: model_decision
description: "Apply when building or reviewing the Next.js frontend and browser-to-gateway interaction."
---

# Frontend quality

Read `docs/FRONTEND_PLAN.md` and `docs/contracts/HTTP_API_V1.md`. Prefer semantic controls, visible labels and explicit loading/empty/error states. Stock values remain server-authoritative. Never put PostgreSQL credentials or raw TCP/NIO protocol logic in browser code. Use SSE as an invalidation signal, followed by REST re-fetch.
