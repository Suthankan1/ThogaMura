# GitHub issue workflow

This project uses issues for individual Java networking evidence and shared work. See `docs/TEAM_WORKLOADS.md` for concepts and `docs/ROADMAP.md` for dependency order.

## Today: plan without assignees

- Use title prefixes `[M1]`, `[M2]`, `[M3]`, `[M4]`, `[M5]`, `[SHARED]`.
- Member numbers are **workload identifiers**, not specific people yet.
- Each issue includes Week/phase, dependencies, acceptance criteria and tests.
- Keep open work unassigned until the five student names and GitHub usernames are confirmed.

## Tomorrow: assign names

1. In the group meeting, confirm which student takes each M1–M5 lecture concept.
2. Invite students to the private repository and verify their GitHub usernames and access.
3. Assign M1 issues to the M1 student, etc. Assign shared issues to one coordinating owner, while all review the integration.
4. Agree on sequence; begin with contract freeze and a real two-JVM socket round trip.
5. Convert the roadmap into milestones or a GitHub Project board if useful, without changing the scope blindly.

## Working an issue

1. Link the issue in a short branch/PR (e.g. `feat/m2-udp-discovery`).
2. Explain the Java API and the precise input/output.
3. Add tests, including failure and disconnect/race cases.
4. Record **actual** commands/output, no invented green results.
5. PR review checks assignment, protocol and security invariants.
6. Update `docs/handoffs/TEMPLATE.md` copy for another member/AI.

Use `.github/ISSUE_TEMPLATE/feature.md` and `bug_report.md` for new work after this initial backlog.
