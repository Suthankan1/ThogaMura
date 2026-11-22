import assert from "node:assert/strict";
import { chromium } from "playwright";

if (process.env.THOGAMURA_VERIFY_DISPOSABLE !== "1") {
  throw new Error("Set THOGAMURA_VERIFY_DISPOSABLE=1 for a fresh synthetic demo stack.");
}

const origin = process.env.THOGAMURA_BROWSER_ORIGIN ?? "http://127.0.0.1:3000";
const browser = await chromium.launch();
try {
  const context = await browser.newContext({ baseURL: origin, viewport: { width: 1280, height: 850 } });
  context.setDefaultTimeout(10000);
  const page = await context.newPage();
  const observer = await context.newPage();
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  observer.on("pageerror", (error) => pageErrors.push(error.message));

  async function stock() {
    const response = await context.request.get("/api/v1/products");
    assert.equal(response.status(), 200);
    const products = await response.json();
    return products.find((item) => item.sku === "ITEM-007");
  }

  await observer.goto("/activity");
  await observer.getByText("Connected · version hints only", { exact: true }).waitFor();
  await observer.getByText("Connected", { exact: true }).waitFor();
  const before = await stock();
  assert.ok(before.available > 0);

  await page.goto("/requests/new?sku=ITEM-007");
  let committedReference;
  const suspendReply = async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    const result = await route.fetch();
    assert.equal(result.status(), 201);
    committedReference = (await result.json()).requestId;
    await route.abort();
  };
  await page.route("**/api/v1/requests", suspendReply);
  await page.getByLabel(/Quantity/).fill("1");
  await page.getByRole("button", { name: "Reserve Stock", exact: true }).click();
  await page.getByRole("heading", { name: "Request outcome unconfirmed" }).waitFor();
  assert.ok(committedReference);
  await page.unroute("**/api/v1/requests", suspendReply);

  await page.reload();
  await page.getByRole("button", { name: "Retry same request" }).click();
  await page.getByRole("heading", { name: "Stock Hold Confirmed" }).waitFor();
  assert.equal((await stock()).reserved, before.reserved + 1);
  console.log("Reservation replay after reload confirmed.");
  try {
    await observer.getByText(/^Version \d+$/).first().waitFor();
  } catch (error) {
    console.error("Gateway status after missing change hint", await (await context.request.get("/api/v1/network-status")).json());
    console.error("Observer state", await observer.locator("main").innerText());
    throw error;
  }
  console.log("Second browser tab received a real warehouse change hint.");

  await page.getByRole("link", { name: "View request" }).click();
  await page.getByText(committedReference, { exact: true }).waitFor();
  await page.getByRole("link", { name: "Open warehouse work" }).click();
  const row = page.getByRole("listitem").filter({ hasText: committedReference.slice(0, 8) });
  await row.getByRole("button", { name: "Mark picked" }).click();
  await page.getByText("Pickup confirmed.", { exact: false }).waitFor();
  console.log("Warehouse pick confirmed.");

  await page.getByRole("button", { name: "Ready to dispatch", exact: true }).click();
  await row.getByRole("button", { name: "Review dispatch" }).click();
  const dialog = page.getByRole("dialog", { name: "Confirm dispatch" });
  await dialog.waitFor();
  assert.equal(await page.evaluate(() => document.activeElement?.textContent?.trim()), "Cancel");
  await page.keyboard.press("Escape");
  await dialog.waitFor({ state: "hidden" });
  await row.getByRole("button", { name: "Review dispatch" }).click();
  const dispatchPath = `**/api/v1/requests/${committedReference}/dispatch`;
  const dropDispatchReply = async (route) => {
    assert.equal((await route.fetch()).status(), 200);
    await route.abort();
  };
  await page.route(dispatchPath, dropDispatchReply);
  await dialog.getByRole("button", { name: "Confirm dispatch", exact: true }).click();
  await page.getByRole("button", { name: "Retry same action" }).waitFor();
  await page.unroute(dispatchPath, dropDispatchReply);
  await page.getByRole("button", { name: "Retry same action" }).click();
  await page.getByText("Dispatch confirmed.", { exact: false }).waitFor();
  assert.equal((await stock()).onHand, before.onHand - 1);
  const ledger = await (await context.request.get("/api/v1/movements")).json();
  assert.equal(ledger.filter((movement) => movement.stockRequestId === committedReference).length, 1);

  await page.goto("/suppliers?sku=ITEM-008");
  await page.getByText("20 units reported", { exact: true }).waitFor();
  await page.goto("/suppliers?sku=ITEM-OUTAGE");
  await page.getByText("ITEM-OUTAGE · Supplier unavailable", { exact: true }).waitFor();

  await page.setViewportSize({ width: 375, height: 812 });
  for (const [path, heading] of [["/", "Operations overview"], ["/products", "Inventory"], ["/requests", "Stock requests"], ["/warehouse", "Warehouse queue"], ["/suppliers", "Supplier availability"]]) {
    await page.goto(path);
    await page.getByRole("heading", { name: heading, exact: true }).waitFor();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), `${path} overflows at 375 px`);
  }

  assert.deepEqual(pageErrors, []);
  console.log("Browser smoke passed: reservation reply loss and reload, SSE, pick and dispatch, supplier failure and mobile layout.");
} finally {
  await browser.close();
}
