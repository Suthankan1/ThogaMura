import type { StockMovement } from "./types/inventory";
import type { Movement } from "./api/workspace";
import { localDateBounds } from "./formatters.ts";

export function filterMovements(rows: StockMovement[], sku: string, from: string, through: string) {
  const search = sku.trim().toLowerCase();
  const range = localDateBounds(from, through);
  return rows.filter((row) => {
    const time = Date.parse(row.occurredAt);
    return (!search || row.sku.toLowerCase().includes(search)) &&
      (!range.from || time >= Date.parse(range.from)) && (!range.before || time < Date.parse(range.before));
  });
}

function quote(value: string | number) {
  const text = typeof value === "string" && /^[\s]*[=+\-@\t\r\n]/.test(value) ? `'${value}` : String(value);
  return `"${text.replaceAll('"', '""')}"`;
}

export function movementCsv(rows: (StockMovement | Movement)[]) {
  const extended = rows.some((row) => "receivingCommandId" in row);
  const headers = extended
    ? ["Movement", "Request reference", "SKU", "Product", "Quantity change", "Reason", "Recorded time", "Receiving command", "Branch", "Recorded by", "Receiving reference"]
    : ["Movement", "Request reference", "SKU", "Quantity change", "Reason", "Recorded time"];
  return [
    headers,
    ...rows.map((row) => {
      if (!extended) {
        return [row.id, row.stockRequestId ?? "", row.sku, row.quantityDelta, row.reason, row.occurredAt];
      }
      const ext = row as Movement;
      return [
        ext.id,
        ext.stockRequestId ?? "",
        ext.sku,
        ext.productName ?? "",
        ext.quantityDelta,
        ext.reason,
        ext.occurredAt,
        ext.receivingCommandId ?? "",
        ext.branchId ?? "",
        ext.recordedBy ?? "",
        ext.note ?? "",
      ];
    }),
  ].map((row) => row.map(quote).join(",")).join("\r\n") + "\r\n";
}

export function downloadCsv(filename: string, csv: string) {
  const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
