import assert from "node:assert/strict";
import { chromium } from "playwright";

// Run on the untouched synthetic fixture; this scenario consumes all eight available carrots.
assert.equal(process.env.THOGAMURA_VERIFY_DISPOSABLE, "1");
const origin = new URL(process.env.THOGAMURA_WORKSPACE_WEB ?? "http://127.0.0.1:3000");
assert.ok(["localhost", "127.0.0.1"].includes(origin.hostname));
const browser = await chromium.launch();
const evidence = process.env.THOGAMURA_DASHBOARD_EVIDENCE;
async function login(name, url = origin.href) {
  const base = new URL(url);
  assert.ok(["localhost", "127.0.0.1"].includes(base.hostname));
  const page = await browser.newPage({ baseURL: base.href, recordVideo: evidence && name !== "picker" ? { dir: evidence } : undefined, viewport: { width: 1280, height: 720 } });
  page.setDefaultTimeout(20000);
  await page.goto("/workspace");
  await page.getByLabel("Account").fill(name);
  await page.getByLabel("Password", { exact: true }).fill(process.env.THOGAMURA_VERIFY_WORKSPACE_PASSWORD);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.getByRole("heading", { name: "First stockout" }).waitFor();
  return page;
}
async function draft(page, quantity) {
  await page.getByRole("link", { name: "Inventory", exact: true }).click();
  await page.getByLabel("SKU", { exact: true }).fill("ITEM-007");
  const form = page.locator("form").filter({ has: page.getByRole("heading", { name: "Reserve stock" }) });
  await form.getByText(/10 on hand · 2 reserved/).waitFor();
  await form.getByLabel("Quantity").fill(quantity);
  return form;
}
try {
  const a = await login("north");
  const b = await login("south", process.env.THOGAMURA_SECOND_WORKSPACE_WEB);
  const draftA = await draft(a, "7");
  const draftB = await draft(b, "2");
  await draftB.getByRole("button", { name: "Reserve stock" }).click();
  await draftA.getByText("8 → 6 available").waitFor();
  await draftA.getByText("Only 6 units are available. Update the quantity to continue.").waitFor();
  assert.equal(await draftA.getByLabel("Quantity").inputValue(), "7");
  if (process.env.THOGAMURA_DASHBOARD_EVIDENCE) {
    await a.screenshot({ path: process.env.THOGAMURA_DASHBOARD_EVIDENCE + "/live-balance.png" });
  }
  assert.ok(await draftA.getByRole("button", { name: "Reserve stock" }).isDisabled());
  const staff = await login("picker");
  for (const [name, width, height] of [["desktop", 1280, 720], ["mobile", 390, 844]]) {
    await staff.setViewportSize({ width, height });
    for (const label of ["Check supplier", "View request"]) {
      const link = staff.getByRole("link", { name: label, exact: true });
      await link.waitFor();
      const bounds = await link.boundingBox();
      assert.ok(bounds && bounds.y >= 0 && bounds.y + bounds.height <= height, label);
    }
    if (process.env.THOGAMURA_DASHBOARD_EVIDENCE) {
      await staff.screenshot({ path: process.env.THOGAMURA_DASHBOARD_EVIDENCE + "/overview-" + name + ".png" });
    }
  }
  await draftA.getByLabel("Quantity").fill("6");
  await draftA.getByRole("button", { name: "Reserve stock" }).click();
  await a.getByText("Reservation confirmed.").waitFor();
  await staff.setViewportSize({ width: 1280, height: 720 });
  await staff.getByRole("link", { name: "View request" }).click();
  await staff.getByRole("list", { name: "Request lifecycle" }).getByText("Accepted", { exact: true }).waitFor();
  await staff.keyboard.press("Escape");
  await staff.getByRole("heading", { name: "First stockout" }).waitFor();
  await staff.goto("/workspace?section=suppliers&sku=ITEM-008");
  await staff.getByText("20 units reported by supplier").waitFor();
  await staff.getByRole("button", { name: "Create follow-up" }).click();
  await staff.getByLabel("Owner").selectOption("picker");
  await staff.getByLabel("Due date").fill("2026-12-01");
  // Commit the creation but lose its HTTP acknowledgement, then retry after reload.
  const createPath = "**/api/v2/supplier-follow-ups";
  let creationKey;
  await staff.route(createPath, async route => {
    creationKey = route.request().headers()["idempotency-key"];
    const committed = await route.fetch();
    assert.equal(committed.status(), 201);
    await route.abort("failed");
  }, { times: 1 });
  await staff.getByRole("button", { name: "Save follow-up" }).click();
  await staff.getByRole("button", { name: "Retry saved action" }).waitFor();
  await staff.reload();
  const retry = staff.waitForRequest(request => request.url().endsWith("/api/v2/supplier-follow-ups") && request.method() === "POST");
  await staff.getByRole("button", { name: "Retry saved action" }).click();
  assert.equal((await retry).headers()["idempotency-key"], creationKey);
  await staff.getByText("Open · Assigned to picker · Due 2026-12-01").waitFor();
  await a.getByRole("link", { name: "Follow-up open" }).click();
  await a.getByText("Open · Assigned to picker · Due 2026-12-01").waitFor();
  assert.equal(await a.getByRole("button", { name: "Close follow-up" }).count(), 0);
  await staff.getByRole("button", { name: "Close follow-up" }).click();
  await a.getByText("Closed · Assigned to picker · Due 2026-12-01").waitFor();
  // Hold a valid outgoing request until a competing branch consumes one of its units.
  await a.getByRole("link", { name: "Inventory", exact: true }).click();
  for (const page of [a, b]) {
    await page.getByLabel("SKU", { exact: true }).fill("ITEM-010");
    await page.locator("form").getByText("4 on hand · 0 reserved").waitFor();
  }
  const intercepted = Promise.withResolvers();
  const release = Promise.withResolvers();
  await a.route("**/api/v2/requests", async route => {
    intercepted.resolve();
    await release.promise;
    await route.continue();
  }, { times: 1 });
  await a.getByLabel("Quantity", { exact: true }).fill("4");
  await a.getByRole("button", { name: "Reserve stock", exact: true }).click();
  await intercepted.promise;
  try {
    await b.getByLabel("Quantity", { exact: true }).fill("1");
    const committed = b.waitForResponse(r => r.url().endsWith("/api/v2/requests") && r.request().method() === "POST");
    await b.getByRole("button", { name: "Reserve stock", exact: true }).click();
    assert.equal((await committed).status(), 201);
  } finally {
    release.resolve();
  }
  await a.getByRole("alert").getByText(/insufficient|exceeds/i).waitFor();
  assert.equal(await a.getByLabel("Quantity", { exact: true }).inputValue(), "4");
  if (evidence) {
    for (const [name, page] of [["north", a], ["south", b]]) {
      const video = page.video();
      await page.close();
      await video.saveAs(evidence + "/branch-" + name + ".webm");
      await video.delete();
    }
  }
  console.log("Verified cross-session stock change, preserved draft, request history, and shared supplier follow-up.");
} finally {
  await browser.close();
}
