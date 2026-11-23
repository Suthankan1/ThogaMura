import assert from "node:assert/strict";
import { chromium } from "playwright";

const browser = await chromium.launch();
const context = await browser.newContext({
  baseURL: process.env.THOGAMURA_BROWSER_ORIGIN ?? "http://127.0.0.1:3000",
  viewport: { width: 1280, height: 720 },
  recordVideo: process.env.THOGAMURA_INTRO_VIDEO ? { dir: process.env.THOGAMURA_INTRO_VIDEO } : undefined,
});
try {
  const page = await context.newPage();
  const intro = page.locator(".brand-intro");
  const home = process.env.THOGAMURA_INTRO_WORKSPACE === "1" ? "/workspace" : "/";
  const entry = home === "/" ? "/products?filter=low_stock" : "/workspace?section=inventory";
  let sawReadDuringIntro = false;
  await page.route("**/api/**", async route => {
    if (/products|session/.test(route.request().url())) sawReadDuringIntro ||= await intro.isVisible();
    if (home === "/workspace" && new URL(route.request().url()).pathname === "/api/v2/session") {
      return route.fulfill({ json: { name: "demo", role: "OPERATOR", branch: "north", csrf: "fixture" } });
    }
    await route.fulfill({ status: 503, contentType: "application/json", body: '{"message":"Fixture unavailable"}' });
  });
  await page.goto(entry, { waitUntil: "domcontentloaded" });
  await intro.waitFor();
  assert.equal(new URL(page.url()).pathname + new URL(page.url()).search, entry);
  await page.waitForFunction(() => document.querySelector(".brand-intro-shell").inert);
  assert.equal(await intro.evaluate(el => el.getAnimations()[0].effect.getComputedTiming().endTime), 2400);
  await intro.waitFor({ state: "detached" });
  assert.ok(sawReadDuringIntro);
  const brand = page.locator('header a[aria-label^="ThogaMura"]');
  if (home === "/workspace") await page.getByLabel("Quantity", { exact: true }).fill("7");
  await brand.click();
  await intro.waitFor();
  await page.waitForURL(url => url.pathname === home && !url.search);
  await intro.waitFor({ state: "detached" });
  assert.equal(await brand.evaluate(el => el === document.activeElement), true);

  // Replay on the current home route; sample both sides of the deadline.
  await brand.press("Enter");
  await intro.waitFor();
  await page.waitForFunction(() => document.querySelector(".brand-intro")?.getAnimations()[0]?.currentTime >= 2100);
  assert.ok(await intro.isVisible());
  await intro.waitFor({ state: "detached", timeout: 1000 });
  if (home === "/") {
    await page.getByRole("navigation", { name: "Primary navigation", exact: true }).getByText("Inventory").click();
    await page.waitForURL("**/products");
    assert.equal(await intro.count(), 0);
    await page.goBack();
    assert.equal(await intro.count(), 0);
  } else {
    await page.getByRole("link", { name: "Inventory", exact: true }).click();
    assert.equal(await page.getByLabel("Quantity", { exact: true }).inputValue(), "7");
    assert.equal(await intro.count(), 0);
  }
  await brand.press("Enter");
  await intro.getByRole("button", { name: "Skip intro" }).click();
  await intro.waitFor({ state: "detached" });
  await brand.press("Enter");
  await page.keyboard.press("Escape");
  await intro.waitFor({ state: "detached" });
  const popupPromise = context.waitForEvent("page");
  await brand.click({ modifiers: ["ControlOrMeta"] });
  const popup = await popupPromise;
  await popup.locator(".brand-intro").waitFor();
  assert.equal(await intro.count(), 0);
  await popup.close();

  await page.setViewportSize({ width: 375, height: 667 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.getByRole("button", { name: "Open navigation menu" }).click();
  await page.getByRole("dialog").locator('a').first().click();
  await intro.waitFor();
  assert.equal(await intro.evaluate(el => el.getAnimations()[0].effect.getComputedTiming().endTime), 160);
  assert.equal(await page.locator(".brand-intro-ring").first().isVisible(), false);
  assert.equal(await page.getByRole("dialog").count(), 0);
  await intro.waitFor({ state: "detached" });

  // Withhold hydration until CSS has finished, as on a slow or failed script download.
  const gate = Promise.withResolvers();
  await page.route("**/_next/static/**/*.js", async route => { await gate.promise; await route.continue(); });
  try {
    await page.reload({ waitUntil: "commit" });
    await intro.waitFor();
    await intro.waitFor({ state: "hidden", timeout: 6500 });
  } finally { gate.resolve(); }
  await intro.waitFor({ state: "detached" });
  assert.equal(await page.locator(".brand-intro-shell").evaluate(el => el.inert), false);
  console.log("Verified intro timing, deep entry, home replay, mobile, reduced motion, focus, and delayed hydration.");
} finally {
  await context.close();
  await browser.close();
}
