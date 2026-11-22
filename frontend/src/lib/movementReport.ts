import type { StockMovement } from "./types/inventory";

export function filterMovements(rows: StockMovement[], sku: string, from: string, through: string) {
  const search = sku.trim().toLowerCase();
  return rows.filter((row) => {
    const day = row.occurredAt.slice(0, 10);
    return (!search || row.sku.toLowerCase().includes(search)) &&
      (!from || day >= from) && (!through || day <= through);
  });
}

function quote(value: string | number) {
  const text = typeof value === "string" && /^[\s]*[=+\-@\t\r\n]/.test(value) ? `'${value}` : String(value);
  return `"${text.replaceAll('"', '""')}"`;
}

export function movementCsv(rows: StockMovement[]) {
  return [
    ["Movement", "Request reference", "SKU", "Quantity change", "Reason", "Recorded time"],
    ...rows.map((row) => [row.id, row.stockRequestId, row.sku, row.quantityDelta, row.reason, row.occurredAt]),
  ].map((row) => row.map(quote).join(",")).join("\r\n") + "\r\n";
}
