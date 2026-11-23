import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import { chromium, webkit } from "playwright";

const products = ["Red Onions", "Ceylon Garlic", "Yukon Gold Potatoes", "Fresh Ginger", "Fresh produce with a long product description"].map((name, index) => ({
  name, sku: ["ITEM-008", "ITEM-011", "ITEM-009", "ITEM-012", "ITEM-013"][index],
  available: index, onHand: index, reserved: 0,
}));
const requests = Array.from({ length: 24 }, (_, i) => ({
  requestId: "fixture-" + i, sku: products[i % products.length].sku,
  quantity: i + 1, status: "PICKED", createdAt: "2026-11-23T12:00:00Z",
}));
const captures = process.env.LAYOUT_CAPTURES;
if (captures) await mkdir(captures, { recursive: true });

for (const engine of [chromium, webkit]) {
  const browser = await engine.launch();
  try {
    const page = await browser.newPage({ baseURL: process.env.THOGAMURA_BROWSER_ORIGIN ?? "http://127.0.0.1:3000", reducedMotion: "reduce" });
    await page.route("**/api/v1/products", route => route.fulfill({ json: products }));
    await page.route("**/api/v1/requests*", route => route.fulfill({ json: route.request().url().includes("ACCEPTED") ? [] : requests }));
    for (const path of ["/", "/products", "/requests"]) {
      await page.goto(path, { waitUntil: "domcontentloaded" });
      await page.getByText("Red Onions", { exact: true }).first().waitFor();
      await page.locator(".brand-intro").waitFor({ state: "hidden" });
      for (const width of [320, 390, 768, 1024, 1440]) {
        await page.setViewportSize({ width, height: 900 });
        await page.waitForFunction(expected => innerWidth === expected, width);
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        if (captures) await page.screenshot({ path: captures + "/" + engine.name() + "-" + (path.slice(1) || "home") + "-" + width + ".png" });
        if (process.env.LAYOUT_BASELINE) continue;
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
        if (path === "/") {
          const metrics = page.getByRole("navigation", { name: "Warehouse totals" });
          const cards = metrics.getByRole("link");
          assert.deepEqual(await cards.locator("strong").allTextContents(), ["0", "24", "1", "3"]);
          const first = await cards.nth(0).locator("strong").boundingBox();
          const next = await cards.nth(1).locator("strong").boundingBox();
          assert.ok(Math.abs(first.y - next.y) <= 1);
          const panel = page.locator('[aria-labelledby="shortages-heading"]');
          assert.ok((await metrics.boundingBox()).y < (await panel.boundingBox()).y);
          for (const row of await panel.locator("li").all()) {
            const badge = await row.locator(".stock-level").boundingBox();
            const action = await row.getByRole("link").boundingBox();
            assert.ok(action.x >= badge.x + badge.width + 8 || action.y >= badge.y + badge.height + 8);
          }
        } else {
          const form = page.locator("main form").first();
          const controls = await Promise.all(["input", "select", "button"].map(tag => form.locator(tag).boundingBox()));
          for (const box of controls) assert.ok(box.height >= 44);
          if (width >= 768) {
            for (const box of controls) assert.ok(Math.abs(box.y + box.height - controls[0].y - controls[0].height) <= 1);
          } else assert.ok(Math.abs(controls[0].width - controls[2].width) <= 1);
        }
      }
      if (!process.env.LAYOUT_BASELINE) {
        await page.evaluate(() => { document.documentElement.style.zoom = "200%"; });
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
        if (captures) await page.screenshot({ path: captures + "/" + engine.name() + "-" + (path.slice(1) || "home") + "-zoom.png" });
        await page.evaluate(() => { document.documentElement.style.zoom = ""; });
      }
    }
    if (!process.env.LAYOUT_BASELINE) {
      const pager = page.getByRole("navigation", { name: "List pages" });
      assert.ok(await pager.getByText("Previous").isDisabled());
      await pager.getByText("Next").click();
      await pager.getByText("Page 2 of 2").waitFor();
      assert.ok(await pager.getByText("Next").isDisabled());
      await page.route("**/api/v1/products", route => route.fulfill({ status: 503, json: { message: "Unavailable" } }));
      await page.goto("/", { waitUntil: "domcontentloaded" });
      const metrics = page.getByRole("navigation", { name: "Warehouse totals" });
      await metrics.getByRole("link", { name: "Ready to dispatch 24" }).waitFor();
      assert.deepEqual(await metrics.locator("strong").allTextContents(), ["0", "24", "—", "—"]);
      const links = metrics.getByRole("link");
      assert.deepEqual(await links.evaluateAll(items => items.map(link => link.getAttribute("href"))),
        ["/warehouse?stage=pick", "/warehouse?stage=dispatch", "/products?filter=out_of_stock", "/products?filter=low_stock"]);
      await page.locator(".brand-intro").waitFor({ state: "detached" });
      await links.first().focus();
      await page.keyboard.press(engine === webkit ? "Alt+Tab" : "Tab");
      assert.ok(await links.nth(1).evaluate(link => document.activeElement === link));
    }
    console.log(engine.name() + " completed layout checks");
  } finally { await browser.close(); }
}
