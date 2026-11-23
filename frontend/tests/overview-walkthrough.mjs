import assert from "node:assert/strict";
import { chromium } from "playwright";

const browser = await chromium.launch();
try {
  const page = await browser.newPage({
    baseURL: process.env.THOGAMURA_BROWSER_ORIGIN ?? "http://127.0.0.1:3000",
  });
  for (const [width, height] of [[1021, 718], [1024, 768], [1280, 800], [1440, 900]]) {
    await page.setViewportSize({ width, height });
    await page.goto("/");
    for (const name of ["Open picking request for Organic Carrots", "Check supplier for Red Onions"]) {
      const link = page.getByRole("link", { name, exact: true }).first();
      await link.waitFor();
      assert.ok((await link.boundingBox()).y + 44 <= height, name);
    }
    await page.getByRole("navigation").getByText("Fulfillment").waitFor();
  }
  await page.getByRole("link", { name: "Check supplier for Red Onions" }).click();
  await page.getByText("20 units reported", { exact: true }).waitFor();
  await page.getByRole("link", { name: "Back to overview" }).click();
  await page.getByRole("link", { name: "New request" }).click();
  const reserve = page.getByRole("button", { name: "Reserve stock", exact: true });
  await reserve.click();
  await page.getByText("Choose a product or enter its SKU.").waitFor();
  assert.equal(await page.locator(":focus").getAttribute("id"), "catalog-picker");
  const picker = page.locator("#catalog-picker");
  await picker.locator('option[value="ITEM-007"]').waitFor({ state: "attached" });
  await picker.selectOption("ITEM-007");
  const max = Number(await page.getByText("Max available:").locator("strong").innerText());
  assert.ok(max > 0);
  for (const value of ["", "0", String(max + 1)]) {
    await page.getByLabel("Quantity", { exact: false }).fill(value);
    await reserve.click();
    await page.getByText(`Enter a quantity from 1 to ${max}.`).waitFor();
  }
  await page.getByRole("link", { name: "Cancel" }).click();
  await page.waitForURL((url) => url.pathname === "/");
  await page.goto("/products?filter=low_stock");
  await page.getByRole("link", { name: "New request" }).click();
  await page.getByRole("link", { name: "Cancel" }).click();
  await page.waitForURL("**/products?filter=low_stock");
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto("/");
  await page.getByRole("link", { name: "Check supplier for Red Onions" }).waitFor();
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  const menu = page.getByRole("button", { name: "Open navigation menu" });
  await menu.click();
  await page.getByRole("dialog").waitFor();
  await page.keyboard.press("Escape");
  assert.ok(await menu.evaluate((el) => el === document.activeElement));
  console.log("Verified desktop decisions, supplier drill-down, quantity limits and return links.");
} finally {
  await browser.close();
}
