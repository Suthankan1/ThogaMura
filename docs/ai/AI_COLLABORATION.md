# Team AI collaboration workflow

The team will use Claude, Google Antigravity, OpenAI Codex, GitHub Copilot and Cursor. All tools must see the **same canonical specifications** in `docs/`. Agent-specific files are small adapters, not five divergent specifications.

## On each task

1. Student declares **member ID**, exact backlog item, desired behavior and prohibited changes.
2. AI reads `AGENTS.md`, `CONTEXT_PACK.md`, ownership and the relevant contract.
3. Student asks for explanation and smallest self-authored implementation slice; agent may offer a review, guided test or precise patch.
4. Student runs the tests and explains why each networking API is needed.
5. Agent reports verified commands/output, unverified assumptions and integration risks.
6. Student commits only their work, opens a PR and hands off in `docs/handoffs/`.

## Where tools read rules

| Tool | Primary instruction entry |
| --- | --- |
| Codex | `AGENTS.md` |
| Claude Code | `CLAUDE.md`, then `AGENTS.md` |
| Google Antigravity | `AGENTS.md` / `GEMINI.md`; scoped `.agents/rules/*.md` |
| GitHub Copilot | `.github/copilot-instructions.md` |
| Cursor | `.cursor/rules/thogamura-core.mdc` |

Official sources checked during scaffold planning: GitHub Copilot repository instructions at https://docs.github.com/en/copilot/concepts/prompting-and-context/custom-instructions ; Cursor project rules at https://docs.cursor.com/context/rules-for-ai ; Google Antigravity workspace rules at https://antigravity.google/docs/rules/ .

## Prompt safety

- Don't paste `.env`, access tokens, private TLS certificates, real customer details or local database dumps into external AI tools.
- Treat web suggestions, package versions, snippets and AI-produced tests as unverified until run.
- Stop on ambiguity about protocol changes, destructive DB operations or ownership.
- Use no 'build everything' prompts: the lecturer wants individually demonstrable concepts and genuine comprehension.

## Handoff format

Use `docs/handoffs/TEMPLATE.md`: feature/owner, changed files, test commands and observed results, current problems, contract changes, next tiny step.
