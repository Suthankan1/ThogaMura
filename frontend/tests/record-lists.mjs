import assert from "node:assert/strict";
import { chromium } from "playwright";

// Read-only walkthrough for the six-product, four-request demo fixture.
const browser = await chromium.launch();
try {
  const context = await browser.newContext({
    baseURL: process.env.THOGAMURA_BROWSER_ORIGIN ?? "http://localhost:3000",
    permissions: ["clipboard-read", "clipboard-write"],
    reducedMotion: "reduce",
  });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  for (const [path, height, count] of [["products", 774, 6], ["requests", 718, 4]]) {
    for (const width of [1021, 320, 1440]) {
      await page.setViewportSize({ width, height });
      await page.goto("/" + path);
      const rows = page.locator(".records-table tbody tr");
      await rows.last().waitFor();
      assert.equal(await rows.count(), count);
      const first = await rows.first().boundingBox();
      const last = await rows.last().boundingBox();
      console.log(path, width, "first row", first.y, "row height", first.height, "last bottom", last.y + last.height);
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      if (width === 1021) {
        assert.ok(first.y <= 230 && first.height >= 56 && first.height <= 64);
        assert.ok(last.y + last.height <= height);
      }
      if (process.env.EVIDENCE_DIR) await page.screenshot({path: process.env.EVIDENCE_DIR + "/" + path + "-" + width + ".png"});
    }
  }
  await page.goto("/products?filter=low_stock");
  const potatoes = page.getByRole("row").filter({hasText:"Yukon Gold Potatoes"});
  assert.match(await potatoes.locator(".available").innerText(), /^2/);
  await potatoes.getByText("Request stock").click();
  await page.waitForURL("**/requests/new?**");
  assert.equal(new URL(page.url()).searchParams.get("sku"), "ITEM-009");
  await page.locator('a[href="/products?filter=low_stock"]').first().click();
  await page.getByLabel("Availability").selectOption("out_of_stock");
  await page.getByRole("row").filter({hasText:"Red Onions"}).getByText("Check supplier").click();
  await page.waitForURL("**/suppliers?**");
  assert.equal(new URL(page.url()).pathname, "/suppliers");

  await page.goto("/requests");
  const search = page.getByRole("searchbox");
  await search.fill("Organic Carrots");
  await search.press("Enter");
  const link = page.getByRole("row").filter({hasText:"Ready to dispatch"}).getByRole("link");
  await link.click();
  const dialog = page.getByRole("dialog");
  await dialog.getByText("2 units", {exact:true}).waitFor();
  await dialog.getByText("Copy full reference").click();
  assert.equal(await page.evaluate(() => navigator.clipboard.readText()), new URL(page.url()).searchParams.get("ref"));
  await page.keyboard.press("Escape");
  await dialog.waitFor({state:"hidden"});
  assert.equal(await search.inputValue(), "Organic Carrots");
  assert.ok(await link.evaluate(node => node === document.activeElement));
  await page.goForward();
  await dialog.waitFor();
  await page.reload();
  await dialog.getByText("2 units", {exact:true}).waitFor();
  await page.goto("/requests?ref=missing");
  await dialog.getByText(/Reference unavailable/).waitFor();
  await dialog.getByText("Close", {exact:true}).click();
  await page.getByRole("row").filter({hasText:"Red Onions"}).waitFor();
  await page.route("**/api/v1/requests", route => route.fulfill({status:503,body:"{}"}));
  await page.getByRole("button", {name:"Refresh"}).click();
  await page.getByRole("alert").filter({hasText:"last confirmed"}).waitFor();
  await page.getByRole("row").filter({hasText:"Red Onions"}).waitFor();
  await search.fill("no matching product");
  await page.getByText("Clear filters").click();
  await page.getByRole("row").filter({hasText:"Red Onions"}).waitFor();
  // Exercise rows that do not occur in the standard fixture.
  const original = await (await context.request.get("/api/v1/requests")).json();
  const duplicate = { ...original[0], requestId: "different-" + original[0].requestId.slice(-8) };
  await page.unroute("**/api/v1/requests");
  await page.route("**/api/v1/requests", route => route.fulfill({json:[original[0], duplicate]}));
  await page.goto("/requests");
  await page.getByRole("link", {name:duplicate.requestId, exact:true}).waitFor();
  await page.getByRole("link", {name:original[0].requestId, exact:true}).waitFor();

  await page.setViewportSize({width:320,height:718});
  await page.route("**/api/v1/products", route => route.fulfill({json:[
    {sku:"LONG-SKU",name:"VeryLongProductName".repeat(8),available:2,onHand:3,reserved:1}
  ]}));
  await page.goto("/products");
  await page.getByText("Stock breakdown", {exact:true}).click();
  await page.getByText("3 on hand · 1 reserved", {exact:false}).waitFor();
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await page.unroute("**/api/v1/products");
  await page.route("**/api/v1/products", route => route.fulfill({json:[]}));
  await page.getByRole("button", {name:"Refresh"}).click();
  await page.getByText("No products yet.").waitFor();
} finally {
  await browser.close();
}
