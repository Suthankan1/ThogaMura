# Final PDF report outline (write from evidence, not assumptions)

1. Title page: project, group names/student IDs, course and submission date.
2. Problem statement, target users, assumptions and real-world relevance.
3. Requirements: functional, non-functional and assignment criteria.
4. Architecture: Next.js -> Spring Boot gateway -> Java TLS/TCP warehouse server; NIO change feed; M2 Java HTTP/URL supplier adapter; PostgreSQL.
5. Protocol contract: newline framing, requestId, error codes, TLS and NIO signaling.
6. Module implementation: one distinct, named section **per member** with source/classes, APIs, screenshots and rationale.
7. Data model and concurrency/idempotency rules.
8. Testing method, actual outputs, race/timeout/disconnect/certificate cases and known failures.
9. UI screenshots and complete request/dispatch scenario.
10. Security scope, limitations and future work.
11. Learning outcomes/contributions and references.

The rendered group-review report is under `reports/final/ThogaMura_Project_Report.pdf`. Add verified student IDs and physical two-device evidence after the group review.

## Submission package

One ZIP with source code and final PDF report, as lecturer specified. Exclude `target/`, `node_modules/`, `.next/`, `.env`, keystores, DB backups and secrets. A designated student submits once on Moodle.
