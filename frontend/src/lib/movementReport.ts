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
  const headers = ["Movement", "Request reference", "SKU", "Quantity change", "Reason", "Recorded time"];
  const extended = rows.some((row) => "receivingCommandId" in row);
  return [
    extended ? [...headers, "Receiving command", "Branch", "Recorded by", "Receiving reference"] : headers,
    ...rows.map((row) => {
      const cells = [row.id, row.stockRequestId ?? "", row.sku, row.quantityDelta, row.reason, row.occurredAt];
      return extended ? [...cells, ...("receivingCommandId" in row ? [row.receivingCommandId ?? "", row.branchId ?? "", row.recordedBy ?? "", row.note ?? ""] : ["", "", "", ""])] : cells;
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
