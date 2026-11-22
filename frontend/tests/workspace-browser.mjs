import assert from "node:assert/strict";
import { chromium } from "playwright";

assert.equal(process.env.THOGAMURA_VERIFY_DISPOSABLE, "1", "Use a fresh disposable protected stack");
const origin = new URL(process.env.THOGAMURA_WORKSPACE_WEB ?? "http://127.0.0.1:3000");
assert.ok(["127.0.0.1", "localhost"].includes(origin.hostname));
const password = process.env.THOGAMURA_VERIFY_WORKSPACE_PASSWORD;
assert.ok(password, "Configure synthetic test accounts");
const browser = await chromium.launch();

try {
  const context = await browser.newContext({ baseURL: origin.href, viewport: { width: 1270, height: 860 } });
  const page = await context.newPage();
  page.setDefaultTimeout(20_000);
  async function signIn(name) {
    await page.getByLabel("Account").fill(name);
    await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page.getByRole("button", { name: "Sign out", exact: true }).waitFor();
    const cookie = (await context.cookies()).find((entry) => entry.name === "THOGAMURA_SESSION");
    assert.ok(cookie?.httpOnly && cookie.sameSite === "Strict");
  }

  await page.goto("/");
  assert.equal(new URL(page.url()).pathname, "/workspace");
  await signIn("north");
  await page.getByRole("heading", { name: "Warehouse inventory" }).waitFor();
  assert.equal(await page.getByRole("button", { name: "Receiving", exact: true }).count(), 0);
  await page.getByLabel("SKU", { exact: true }).fill("ITEM-010");
  await page.getByLabel("Quantity", { exact: true }).fill("1");
  await page.getByRole("button", { name: "Reserve stock", exact: true }).click();
  await page.getByRole("button", { name: "Open request", exact: true }).click();
  await page.getByRole("heading", { name: "Request details" }).waitFor();
  const detailUrl = page.url();
  assert.ok(new URL(detailUrl).searchParams.get("request"));
  await page.reload();
  await page.getByRole("button", { name: "Cancel reservation" }).click();
  await page.getByRole("button", { name: "Confirm cancel" }).click();
  await page.getByText("Cancellation confirmed.").waitFor();
  await page.getByText("Cancelled", { exact: true }).waitFor();

  await page.getByRole("button", { name: "Sign out" }).click();
  await signIn("south");
  await page.goto(detailUrl);
  await page.getByText("Stock request was not found").waitFor();
  await page.getByRole("button", { name: "Sign out" }).click();
  await signIn("supervisor");

  await page.getByRole("button", { name: "Receiving", exact: true }).click();
  await page.getByLabel("SKU", { exact: true }).fill("ITEM-010");
  await page.getByLabel("Quantity", { exact: true }).fill("3");
  await page.getByLabel("Receiving reference").fill("Browser replay fixture");
  let droppedKey;
  const pattern = "**/api/v2/receiving";
  await page.route(pattern, async (route) => {
    droppedKey = route.request().headers()["idempotency-key"];
    const upstream = await route.fetch();
    assert.equal(upstream.status(), 201);
    await route.abort();
  });
  await page.getByRole("button", { name: "Receive stock", exact: true }).click();
  await page.getByRole("button", { name: "Retry saved action" }).waitFor();
  await page.unroute(pattern);
  await page.reload();
  const retry = page.waitForRequest((request) => request.url().endsWith("/api/v2/receiving"));
  await page.getByRole("button", { name: "Retry saved action" }).click();
  assert.equal((await retry).headers()["idempotency-key"], droppedKey);
  await page.getByText("Receiving confirmed.").waitFor();

  await page.getByRole("button", { name: "Movements", exact: true }).click();
  await page.getByText("supervisor · Browser replay fixture", { exact: true }).waitFor();
  assert.equal(await page.getByText("supervisor · Browser replay fixture", { exact: true }).count(), 1);
  if (process.env.THOGAMURA_WORKSPACE_SCREENSHOT) {
    await page.screenshot({ path: process.env.THOGAMURA_WORKSPACE_SCREENSHOT, fullPage: true });
  }

  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "Inventory", exact: true }).click();
  await page.getByRole("heading", { name: "Warehouse inventory" }).waitFor();
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  await page.getByRole("button", { name: "Suppliers", exact: true }).click();
  await page.getByLabel("SKU", { exact: true }).fill("ITEM-007");
  await page.getByRole("button", { name: "Check supplier" }).click();
  await page.getByText(/units reported by supplier/).waitFor();
  console.log("Chromium: same-origin session, reloadable exact lookup, cancel, branch denial, lost receiving reply and replay, one ledger entry, supplier and 390px layout passed");
} finally {
  await browser.close();
}
