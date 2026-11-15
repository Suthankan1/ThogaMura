# Assignment 02 alignment

## Explicit requirements from assignment brief

| Brief | Where demonstrated | Evidence needed |
| --- | --- | --- |
| Build Java network programming application (`java.net`) | Command TCP client/server and UDP discovery | Real two-process network recording + source |
| Demonstrate Java NIO | Non-blocking selector-based notification feed | Several simultaneous clients, single selector loop |
| Each group member individually demonstrates distinct lecture concept | Five workloads in `TEAM_WORKLOADS.md` | Per-member tests, owned code, oral explanation |
| Project + PDF report in one ZIP | Final submission package | Checked ZIP with sources, README, report PDF |
| One group submission via Moodle | Designated team representative | Lecturer-provided submission process |

Spring Boot REST and Next.js are **supporting interfaces**, not substitutes for assessment concepts. Do not count standard HTTP controllers as Member 1's socket implementation. Do not count browser EventSource as Member 3's Java NIO implementation.

## Evidence per member

Each member should show:

1. Named module/classes they wrote;
2. actual lecture API and call path;
3. useful happy path;
4. deliberate failure (timeout, duplicate, disconnect, concurrent race or certificate rejection);
5. automated verification with command/output;
6. explanation of how their component fits the full request flow.

If lecturer specifies different grading rules, update this file and `TEAM_WORKLOADS.md`.
