# Planned browser HTTP client

Use typed DTOs from `docs/contracts/HTTP_API_V1.md`. All browser calls go to Spring Boot gateway and must handle `400/404/409/503/504` errors correctly. Generate a UUID once for a logical stock request and send it as `Idempotency-Key`; preserve it on retry. No implementation is present in this starter.
