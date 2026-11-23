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
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page.getByRole("button", { name: "Sign out", exact: true }).waitFor();
    const cookie = (await context.cookies()).find((entry) => entry.name === "THOGAMURA_SESSION");
    assert.ok(cookie?.httpOnly && cookie.sameSite === "Strict");
  }

  await page.goto("/");
  assert.equal(new URL(page.url()).pathname, "/workspace");
  await page.getByLabel("Account").fill("north");
  await page.getByLabel("Password", { exact: true }).fill(password + "-invalid");
  await page.getByRole("button", { name: "Show password" }).click();
  assert.equal(await page.getByLabel("Password", { exact: true }).getAttribute("type"), "text");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  const error = page.getByRole("alert").filter({ hasText: "Sign-in failed" });
  await error.waitFor();
  assert.equal(await page.getByLabel("Account").inputValue(), "north");
  assert.equal(await page.getByLabel("Password", { exact: true }).inputValue(), "");
  assert.ok(await error.evaluate((el) => el.parentElement === document.activeElement));
  await signIn("north");
  await page.getByRole("heading", { name: "First stockout" }).waitFor();
  await page.getByRole("link", { name: "Inventory", exact: true }).click();
  await page.getByRole("heading", { name: "Warehouse inventory" }).waitFor();
  assert.equal(await page.getByRole("link", { name: "Receiving", exact: true }).count(), 0);
  await page.getByLabel("SKU", { exact: true }).fill("ITEM-010");
  await page.getByLabel("Quantity", { exact: true }).fill("1e3");
  await page.getByRole("button", { name: "Reserve stock", exact: true }).click();
  await page.getByText("Enter a whole number from 1 to 2,147,483,647.").waitFor();
  await page.getByLabel("Quantity", { exact: true }).fill("1");
  await page.getByRole("link", { name: "Requests", exact: true }).click();
  await page.getByRole("link", { name: "Inventory", exact: true }).click();
  assert.equal(await page.getByLabel("SKU", { exact: true }).inputValue(), "ITEM-010");
  assert.equal(await page.getByLabel("Quantity", { exact: true }).inputValue(), "1");
  await page.locator("form").filter({ has: page.getByRole("heading", { name: "Reserve stock" }) }).getByText(/on hand ·/).waitFor();
  await page.getByRole("button", { name: "Reserve stock", exact: true }).click();
  await page.getByRole("link", { name: "Open request", exact: true }).click();
  await page.getByRole("heading", { name: "Request details" }).waitFor();
  const detailUrl = page.url();
  assert.ok(new URL(detailUrl).searchParams.get("request"));
  await page.reload();
  await page.getByRole("button", { name: "Cancel reservation" }).click();
  const cancel = page.getByRole("dialog", { name: "Cancel reservation" });
  await cancel.waitFor();
  assert.equal(await page.evaluate(() => document.activeElement?.textContent), "Back");
  await cancel.getByRole("button", { name: "Confirm cancel" }).click();
  await page.getByText("Cancellation confirmed.").waitFor();
  await page.getByRole("list", { name: "Request lifecycle" }).getByText("Cancelled", { exact: true }).waitFor();

  await page.getByRole("button", { name: "Sign out" }).click();
  await signIn("south");
  await page.goto(detailUrl);
  await page.getByText("Stock request was not found").waitFor();
  await page.getByRole("button", { name: "Sign out" }).click();
  await signIn("supervisor");

  await page.getByRole("link", { name: "Receiving", exact: true }).click();
  await page.getByLabel("SKU", { exact: true }).fill("ITEM-010");
  await page.getByLabel("Quantity", { exact: true }).fill("3");
  await page.getByLabel("Receiving reference").fill("Browser replay fixture");
  let droppedKey;
  let savedBody;
  const pattern = "**/api/v2/receiving";
  await page.route(pattern, async (route) => {
    droppedKey = route.request().headers()["idempotency-key"];
    savedBody = route.request().postDataJSON();
    const upstream = await route.fetch();
    assert.equal(upstream.status(), 201);
    await route.fulfill({ status: 201, json: {} });
  });
  await page.getByText(/on hand ·/).waitFor();
  await page.getByRole("button", { name: "Review receipt", exact: true }).click();
  await page.getByRole("dialog", { name: "Review receiving" }).getByRole("button", { name: "Confirm receipt" }).click();
  await page.getByRole("button", { name: "Retry saved action" }).waitFor();
  assert.equal(await page.getByText("Receiving confirmed.", { exact: true }).count(), 0);
  await page.getByText(`Command ${droppedKey}`, { exact: true }).waitFor();
  await page.unroute(pattern);
  await page.reload();
  const retry = page.waitForRequest((request) => request.url().endsWith("/api/v2/receiving"));
  await page.getByRole("button", { name: "Retry saved action" }).click();
  const retried = await retry;
  assert.equal(retried.headers()["idempotency-key"], droppedKey);
  assert.deepEqual(retried.postDataJSON(), savedBody);
  await page.getByText("Receiving confirmed.").waitFor();

  await page.getByRole("link", { name: "Movements", exact: true }).click();
  await page.getByText("supervisor · Browser replay fixture", { exact: true }).waitFor();
  assert.equal(await page.getByText("supervisor · Browser replay fixture", { exact: true }).count(), 1);
  await page.getByLabel("Movement type").selectOption("RECEIVE");
  await page.getByLabel("SKU, reference, branch, or note").fill(droppedKey.slice(0, 8));
  await page.getByRole("button", { name: "Apply filters" }).click();
  await page.reload();
  await page.getByText("supervisor · Browser replay fixture", { exact: true }).waitFor();
  assert.equal(await page.getByLabel("Movement type").inputValue(), "RECEIVE");
  if (process.env.THOGAMURA_WORKSPACE_SCREENSHOT) {
    await page.screenshot({ path: process.env.THOGAMURA_WORKSPACE_SCREENSHOT, fullPage: true });
  }

  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("link", { name: "Inventory", exact: true }).click();
  await page.getByRole("heading", { name: "Warehouse inventory" }).waitFor();
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  await page.goto("/activity");
  await page.getByText(/Catalog read confirmed/).waitFor();
  await page.getByRole("link", { name: "Run a live inquiry" }).click();
  await page.getByRole("heading", { name: "Supplier availability" }).waitFor();
  assert.equal(new URL(page.url()).searchParams.get("section"), "suppliers");
  await page.getByLabel("SKU", { exact: true }).fill("ITEM-007");
  await page.getByRole("button", { name: "Check supplier" }).click();
  await page.getByText(/units reported by supplier/).waitFor();
  const recheck = page.waitForRequest((request) => request.url().includes("/api/v2/suppliers/availability?sku=ITEM-007"));
  await page.getByRole("button", { name: "Check supplier" }).click();
  await recheck;
  await page.getByText("Supplier simulator").waitFor();
  await page.getByRole("link", { name: "Requests", exact: true }).click();
  await page.getByRole("heading", { name: "Stock requests", exact: true }).waitFor();
  await page.getByLabel("Product, request reference, or branch").fill("north");
  const filteredRead = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === "/api/v2/requests" && url.searchParams.get("search") === "north" && response.ok();
  });
  await page.getByRole("button", { name: "Apply filters" }).click();
  await filteredRead;
  await page.setViewportSize({ width: 320, height: 650 });
  const requestList = page.locator('section[aria-busy="false"]').filter({ has: page.getByRole("heading", { name: "Stock requests", exact: true }) });
  const requestLink = requestList.getByRole("link", { name: /^Open request [a-f0-9]/ }).first();
  await requestLink.scrollIntoViewIfNeeded();
  await requestLink.focus();
  const previousPosition = await page.evaluate(() => window.scrollY);
  await requestLink.press("Enter");
  await page.getByRole("heading", { name: "Request details" }).waitFor();
  await page.getByRole("link", { name: "Back to requests" }).click();
  await requestLink.waitFor();
  try {
    await page.waitForFunction((position) => Math.abs(window.scrollY - position) < 3, previousPosition, { timeout: 5000 });
  } catch (error) {
    const restored = await page.evaluate(() => ({
      top: window.scrollY,
      saved: Object.entries(sessionStorage).filter(([key]) => key.includes(":scroll:requests:north:")),
    }));
    throw new Error(`Request position ${previousPosition} was not restored: ${JSON.stringify(restored)}`, { cause: error });
  }
  assert.equal(await page.getByLabel("Product, request reference, or branch").inputValue(), "north");
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  await page.getByRole("button", { name: "Sign out" }).click();
  await signIn("picker");
  await page.getByRole("link", { name: "Fulfillment queue", exact: true }).click();
  await page.getByRole("heading", { name: "Fulfillment queue", exact: true }).waitFor();
  await page.getByRole("button", { name: "Sign out" }).click();
  await page.goto("/activity");
  await page.getByText("Sign in to check inventory", { exact: false }).waitFor();
  console.log("Chromium: account recovery, draft, cancellation and scope, malformed receiving reply and replay, filtered ledger, authenticated diagnostics, supplier refresh and mobile width passed");
} finally {
  await browser.close();
}
