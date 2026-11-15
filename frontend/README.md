# Next.js web starter

Current status: routes and design shell only, no business API calls. Each screen says explicitly that the backend is not connected.

```bash
cd frontend
npm ci
npm run dev
npm run typecheck
npm run lint
npm run build
```

Read `../docs/FRONTEND_PLAN.md` and `../docs/contracts/HTTP_API_V1.md` before adding forms or network code. Use semantic HTML, labelled inputs, an accessible responsive table, explicit loading/empty/error states and no optimistic stock values. Browser communicates with Spring Boot gateway via HTTP/SSE, never direct Java sockets or PostgreSQL.

Environment template: copy `.env.example` to `.env.local`. Only safe public addresses belong in `NEXT_PUBLIC_*` variables.
