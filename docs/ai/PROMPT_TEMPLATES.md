# Copy-ready prompts for any coding assistant

Replace bracketed fields and paste **one task at a time**. Reference the local documents so any tool shares the same project truth.

## First message to an AI after opening the project

```text
We are building ThogaMura, a 5-member Java Network Programming Assignment 02 project.
Read AGENTS.md, docs/ai/CONTEXT_PACK.md, docs/TEAM_WORKLOADS.md,
docs/ARCHITECTURE.md and the relevant docs/contracts files first.
I am Member [N], responsible for [lecture concept]. Do not implement
any feature yet. Explain my component's exact input/output and its
integration dependencies. List the next three small testable steps.
```

## Implement a small slice

```text
Owner: Member [N]. Task: [one observable behavior].
Read our frozen contracts and existing tests. Explain the JDK APIs
involved, suggest a minimal implementation plan and wait for me to
confirm the public interface if it changes. Keep changes limited to
[paths]. Include a real failure-path test. No unrelated refactors.
```

## Review student-written code

```text
Review only these files: [paths]. Check lecture concept correctness,
protocol compatibility, Java resource cleanup, concurrency/safety,
negative tests and build claims. Give line-specific actionable fixes.
Do not rewrite the whole component unless I explicitly ask.
```

## Integration debug

```text
We need to debug [reproduction] across Next.js -> gateway -> Java socket
server -> PostgreSQL. Here are the sanitized logs [paste].
Distinguish a problem in HTTP mapping, socket framing, TLS, NIO or the DB
transaction. Propose read-only checks first, then the smallest fix and a
regression test. Preserve requestId.
```

## Before PR / handoff

```text
Compare the changes to AGENTS.md and docs/TEAM_WORKLOADS.md.
Show which commands really ran, expected vs observed outcomes,
any contract deviation, risks, and next owner integration point.
Draft docs/handoffs/member-[N].md using the handoff template.
```
