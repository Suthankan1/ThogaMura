# GitHub issue workflow

This project uses issues for individual Java networking evidence and shared work. Design v2 replaces M2 UDP auto-discovery with read-only Java URL/HTTP supplier availability (see `docs/NETWORK_FEATURES_V2.md`). Issues #15–#17 are historical superseded tasks; do not assign them. See `docs/TEAM_WORKLOADS.md` for concepts and `docs/ROADMAP.md` for dependency order.

## Confirmed owners and current GitHub assignment (18 November 2026)

- Use title prefixes `[M1]`, `[M2]`, `[M3]`, `[M4]`, `[M5]`, `[SHARED]`.
- Member-to-handle mapping and the complete shared lead list are in [issue #1](https://github.com/Suthankan1/ThogaMura/issues/1).
- M3 @Suthankan1, M4 @Yasiru-ruwantha and M5 @vibhathkalsara99 are assigned their respective feature issues and appropriate shared issues.
- M1 @sankharavindu and M2 @sandunikarunarathna are **confirmed intended owners, but GitHub reports repository permission `none`**. The assignee API does not persist them. Those 12 open issues remain *actually unassigned* until both users are invited to the private repo and accept.
- #15, #16 and #17 (old UDP discovery work) stay closed/not planned. The NIO private-network security issue is #33.
- Each issue includes week/phase, dependencies, acceptance criteria and test evidence requirements. Do not close tickets without working proof.

## Next ownership actions

1. The repo owner invites @sankharavindu and @sandunikarunarathna with appropriate least-privilege collaborator access; wait for acceptance.
2. Assign M1: #6, #7, #11, #14, plus shared #4 and #9.
3. Assign M2: #31, #32, #30, plus shared #2, #13 and #26.
4. Review all five concepts with the lecturer (especially HTTP/URL for M2) and freeze contracts.
5. Begin with a real two-JVM network round trip; run each member's positive and negative demos.
6. Optionally set milestones/Project board without changing approved scope.

## Working an issue

1. Link the issue in a short branch/PR (e.g. `feat/m2-supplier-http`).
2. Explain the Java API and the precise input/output.
3. Add tests, including failure and disconnect/race cases.
4. Record **actual** commands/output, no invented green results.
5. PR review checks assignment, protocol and security invariants.
6. Update `docs/handoffs/TEMPLATE.md` copy for another member/AI.

Use `.github/ISSUE_TEMPLATE/feature.md` and `bug_report.md` for new work after this initial backlog.
