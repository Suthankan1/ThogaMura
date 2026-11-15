# ThogaMura

**LAN-first stock requests and warehouse fulfilment** — Java Network Programming, Assignment 02.

> **Status: architecture and project scaffold, not a completed application.** The Spring Boot and Next.js skeletons do not yet implement stock requests or network listeners. Each networking feature must be written, tested and demonstrated by its assigned student.

## Why this project

A small shop can request stock from a warehouse on the same local network. The server reserves stock without overselling; warehouse staff pick and dispatch an accepted request. Operators see changes promptly, retry safely after an uncertain network response and retain a persistent audit history.

## Stack

| Area | Choice |
| --- | --- |
| UI | Next.js App Router, React, TypeScript, Tailwind CSS |
| Web bridge | Spring Boot REST + Server-Sent Events (SSE) |
| Networking | Explicit Java `java.net` TCP, UDP, `SSLSocket` and Java NIO `SocketChannel`/`Selector` |
| Network server | Spring Boot lifecycle hosting hand-written Java socket services |
| Data | PostgreSQL, Spring JDBC, Flyway migrations |
| Dev/test | Java 21, Maven, Node.js 22+, npm, Docker Compose, JUnit 5 |

## Architecture

```text
Browser (Next.js :3000)
          | HTTPS/HTTP REST + SSE
          v
API gateway (Spring Boot :8080)
  |        |                  ^
  |        +-- UDP discover --|      Java sockets live here, NOT in browser
  |                          |
  +-- TLS/TCP commands ------+----> Network server (Spring Boot host, no REST)
  +<------ NIO change feed -------   - Command service TLS :7443
                                    - NIO change feed :7444
                                    - UDP discovery :7445
                                    - PostgreSQL :5432 (server only)
```

**Non-negotiable:** The gateway must use actual network sockets to speak to the network server, even when both processes run on the same laptop. It may not bypass the network demonstration by calling a Java service in-process or connecting directly to PostgreSQL.

## Project navigation

- [Start here](docs/START_HERE.md): group decisions and first setup.
- [Project context](docs/PROJECT_CONTEXT.md): problem, users, scope and success criteria.
- [Architecture](docs/ARCHITECTURE.md): components, message paths and runtime boundaries.
- [Protocol v1](docs/contracts/NETWORK_PROTOCOL_V1.md) and [HTTP API](docs/contracts/HTTP_API_V1.md): shared contracts; freeze before parallel implementation.
- [Frontend specification](docs/FRONTEND_PLAN.md): screens, states and accessibility.
- [Backend specification](docs/BACKEND_PLAN.md): applications, data and transactions.
- [Work ownership](docs/TEAM_WORKLOADS.md): five distinct assessed concepts.
- [Implementation roadmap](docs/ROADMAP.md) and [test plan](docs/TEST_PLAN.md).
- [AI working agreement](docs/ai/AI_COLLABORATION.md), [context pack](docs/ai/CONTEXT_PACK.md) and [prompt templates](docs/ai/PROMPT_TEMPLATES.md).
- [Lecture mapping](docs/LECTURE_MAPPING.md), [report plan](docs/REPORT_OUTLINE.md) and [demo script](docs/DEMO_SCRIPT.md).
- [Original group proposal](docs/proposal/ThogaMura_Group_Proposal.pdf) (historical stack; [revision note](docs/proposal/README.md)).

## Structure

```text
ThogaMura/
├── AGENTS.md / CLAUDE.md / GEMINI.md
├── .agents/rules/       # Google Antigravity
├── .cursor/rules/       # Cursor
├── .github/             # Copilot + CI + PR template
├── backend/
│   ├── protocol/        # Shared message conventions
│   ├── network-server/  # TCP, UDP, NIO, TLS, concurrency and database
│   └── api-gateway/     # REST, SSE, socket clients
├── frontend/            # Next.js App Router starter
├── docs/                # Architecture, contracts, assignments, AI context
├── scripts/
├── compose.yaml         # Local PostgreSQL only
└── .env.example
```

## First commands

Requirements: Java 21, Maven 3.9+, Node.js 22+, npm and Docker with Compose.

```bash
cp .env.example .env
# Edit .env and choose your own local-only PostgreSQL password first.
docker compose up -d db
mvn -f backend/pom.xml clean verify
npm --prefix frontend ci
npm --prefix frontend run dev
```

The UI starter opens at http://localhost:3000. For gateway and network server processes see [Setup](docs/SETUP.md). At this stage the UI is an informational placeholder and the network server is **not** listening on socket ports.

## Course requirements

The assignment requires Java `java.net`, meaningful Java NIO usage, a distinct lecture networking feature per group member, and one ZIP containing the finished code plus one PDF report. See [Assignment alignment](docs/ASSIGNMENT_ALIGNMENT.md).

## Team rules

1. Freeze shared protocols first. Changing an envelope or field requires an ADR and review.
2. One feature owner = own source, focused tests, failure demonstration and explanation.
3. Small PRs, no generated full-feature dumps, no mixing unrelated work.
4. No real customer data, credentials, keystores, passwords or database volumes in Git.
5. Keep business invariants and network edge cases tested before polishing the UI.

Repository: https://github.com/Suthankan1/ThogaMura
