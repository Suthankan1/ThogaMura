# ThogaMura setup and verification

Use Java 21, Maven 3.9+, Node.js 22+, Docker Compose, Python 3, curl and nc. Run commands from the repository root. Docker must be running for PostgreSQL integration tests.

## Local operator demo

```bash
cp .env.example .env
# Set local PostgreSQL credentials in .env before the first start.
./start.sh
./start.sh seed
./status.sh
./stop.sh
```

Open http://127.0.0.1:3000. The runner starts loopback listeners and checks the actual warehouse command path through the gateway. The seeder writes synthetic data only when all inventory tables are empty. Repeating it preserves current balances and history. The runner stops its own services and the database container it started. Use `./start.sh logs server` or substitute `gateway`, `frontend` or `supplier` for other logs.

Set `POSTGRES_PORT` and its matching `THOGAMURA_DB_URL` in `.env` if port 5432 is occupied. The other runner ports can be set with `THOGAMURA_COMMAND_PORT`, `THOGAMURA_NOTIFICATIONS_PORT`, `THOGAMURA_SUPPLIER_SIMULATOR_PORT`, `THOGAMURA_GATEWAY_PORT` and `THOGAMURA_FRONTEND_PORT`. Set the supplier base URL to the matching simulator port when changing it. Use `THOGAMURA_FRONTEND_WEBPACK=1` if the local environment cannot start Turbopack workers.

To run a production build locally, build with the matching gateway rewrite and set `THOGAMURA_FRONTEND_MODE=start` before running the stack. For example, `GATEWAY_URL=http://127.0.0.1:8080 npm --prefix frontend run build`.

The web app defaults to its same-origin Next.js rewrite. Set `GATEWAY_URL` when running the frontend separately on a branch with a custom gateway port. Keep credentials outside `NEXT_PUBLIC_*` variables.

## Automated checks

```bash
mvn -f backend/pom.xml clean verify
npm --prefix frontend ci
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run build
```

The backend tests create a private disposable PostgreSQL container. They ignore application database environment variables and project dotenv. The frontend typecheck generates route types before running TypeScript.

On a fresh disposable demo stack, exercise the real HTTP-to-Java workflow and browser UI.

```bash
THOGAMURA_VERIFY_DISPOSABLE=1 ./scripts/verify-full-stock-workflow.sh
npm --prefix frontend exec -- playwright install chromium
THOGAMURA_VERIFY_DISPOSABLE=1 npm --prefix frontend run test:browser
```

The browser test drives the app at `THOGAMURA_BROWSER_ORIGIN` when set and defaults to localhost port 3000. It consumes one synthetic unit. The HTTP verifier uses `THOGAMURA_GATEWAY_URL` when set.

## Two-machine TLS demo

Package the two JVMs using the Maven reactor and run them separately. Generate the warehouse keystore and branch truststore with the actual warehouse IP or DNS name in the certificate SAN. [Certificate setup](TLS_CERTIFICATES.md)

- On the warehouse, run PostgreSQL on loopback and forward DB credentials to the network-server JVM only. Set `THOGAMURA_COMMAND_SERVER_ENABLED=true`, `THOGAMURA_COMMAND_SERVER_HOST=<private warehouse IP>` and `THOGAMURA_TLS_SERVER_ENABLED=true`. Configure the warehouse keystore path and password.
- Enable NIO using `THOGAMURA_NOTIFICATIONS_ENABLED=true`, `THOGAMURA_NOTIFICATIONS_HOST=<private warehouse IP>`, `THOGAMURA_NOTIFICATIONS_ALLOW_NON_LOOPBACK=true` and `THOGAMURA_NOTIFICATIONS_ALLOWED_CLIENTS=<exact private branch IPs>`. Allow the command and NIO ports only from the demo branch peers through the warehouse firewall.
- On the branch, set `THOGAMURA_WAREHOUSE_HOST` to the SAN identity and `THOGAMURA_NOTIFICATIONS_HOST` to the warehouse private address. Set `THOGAMURA_TLS_CLIENT_ENABLED=true` and `THOGAMURA_NOTIFICATIONS_SUBSCRIBER_ENABLED=true`. Configure the branch truststore path and password before running the gateway. Start Next.js and open it from the branch.
- Query `/api/v1/network-status` through the branch gateway and verify operational server-authenticated TLS and the connected NIO feed. Include wrong identity rejection and authorized and unauthorized NIO peer checks in the rehearsal. [Demo script](DEMO_SCRIPT.md)

## Authenticated branch workspace

Use the workspace profile on a fresh or explicitly assigned environment. Keep the gateway and Next.js on the same browser origin via the Next.js rewrite. Use browser-facing HTTPS and secure cookies when opening the application beyond loopback. Keep both Java listeners within the documented private network. Existing v1 demo scripts use the default profile.

1. Configure validated command TLS using the stores and certificate SAN from the TLS instructions above.
2. Set `THOGAMURA_ACCESS_ENABLED=true` on both JVMs. Generate one random Base64 signing value with `openssl rand -base64 32` and configure it as `THOGAMURA_ACCESS_SIGNING_KEY` on both JVMs. Keep the signing value in local secret configuration.
3. Generate each gateway login with `python3 scripts/workspace-account.py north OPERATOR north`. Create another operator for a different branch and add warehouse accounts using `PICKER` and `SUPERVISOR`. Join the generated records with semicolons in the gateway-only `THOGAMURA_ACCESS_USERS` setting. Single-quote the value when using the runner’s `.env`.
4. For a loopback demo, start the app and load the synthetic fixtures into a fresh PostgreSQL database. Browse to `http://127.0.0.1:3000/workspace`. Both JVMs must restart when switching profiles. Set `THOGAMURA_ACCESS_ENABLED=true` in the frontend build environment to make the workspace the default entry point. The local runner supplies this setting to Next.js. Flyway applies pending migrations on warehouse startup.
5. Sign in as a branch operator, reserve stock, and open the exact request URL. Try the URL as another branch operator, then sign in as a picker or supervisor to exercise warehouse permissions and inspect the supervisor ledger.

To run `scripts/verify-workspace.py`, use a fresh disposable protected gateway with the seeded inventory and synthetic accounts `north`, `south`, `picker` and `supervisor`, configured as in the script. Set `THOGAMURA_VERIFY_DISPOSABLE=1`, `THOGAMURA_VERIFY_WORKSPACE_PASSWORD` and the loopback `THOGAMURA_GATEWAY_URL`. The test writes inventory and assumes its own isolated fixture. The browser test `node frontend/tests/workspace-browser.mjs` uses the same inputs plus `THOGAMURA_WORKSPACE_WEB` pointing to the Next.js origin.

For the frontend audit demonstration, repeat the Chromium scripts with the demo and protected profiles on separate disposable databases. On the demo profile, open Inventory and Requests at 375 px and confirm that Search and its adjacent controls have separate hit regions. At 740 × 360, open the navigation drawer and scroll to the Diagnostics link. On the protected profile, use Diagnostics to confirm the authenticated inventory read, open the supplier link, and submit an inquiry twice for the same SKU. Review a delivery as a supervisor. The browser test forwards that receipt to the warehouse and substitutes an empty HTTP 201 body. Verify that the UI retains the command, retries the original payload and UUID after reload, and displays one movement for the delivery.

For the extended v1 checks, reset to a new disposable TLS-enabled demo stack with NIO and the standard synthetic seed. Run `THOGAMURA_VERIFY_DISPOSABLE=1 python3 scripts/verify-network-load.py`, with `THOGAMURA_GATEWAY_URL` set to the loopback branch gateway. The script captures 48 command timings, checks 12 SSE listeners, and exercises competing reservation and concurrent dispatch. See the recorded topology and separate physical-device procedure. [Network evidence](../reports/evidence/segmented_lan_closeout.txt) · [Workspace contract](contracts/WORKSPACE_V2.md)

### Operator dashboard rehearsal

Flyway V4 creates the follow-up and request-event tables. Configure the warehouse environment variable THOGAMURA_FOLLOWUP_OWNERS with up to 24 comma-separated provisioned picker/supervisor account names, for example `picker,supervisor`. With an empty roster, signed-in warehouse staff can assign themselves.

On a **fresh disposable** protected fixture, run `node frontend/tests/workspace-stock-demo.mjs` with the browser-test variables above. Set THOGAMURA_SECOND_WORKSPACE_WEB to a second loopback Next.js instance backed by a separate NIO-subscribing gateway to exercise two gateways. Each frontend must use its own gateway for SSE and authenticated reads. Set THOGAMURA_DASHBOARD_EVIDENCE to a directory to capture the branch recordings and overview screenshots. This scenario consumes the eight available carrots and exercises a competing request for Fresh Leeks. Use a separate fresh fixture for verify-workspace.py.

For the manual demo, sign into separate branch sessions. In A, enter seven Organic Carrots. In B, reserve two. Watch A show 8 → 6, retain seven, and disable submission. Change the draft to six and reserve. Open the request as a picker, pick it and dispatch it, then inspect its timestamps. Check Red Onions, assign a due date and owner, and open the same follow-up from a branch inventory row. Disconnect one branch subscriber, change inventory elsewhere, then reconnect and confirm the branch refetches the warehouse balance.
