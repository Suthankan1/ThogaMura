# Planned typed DTOs

Mirror the stable browser-facing shapes from `docs/contracts/HTTP_API_V1.md` here, not Java persistence entities or raw socket frames. Add runtime validation at the gateway boundary. Use an explicit `unknown` response and map errors; never cast untrusted JSON blindly.
