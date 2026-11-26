"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { fetchProducts, fetchSupplierAvailability } from "@/lib/api/inventory";
import { formatFullDateTime, formatUnits } from "@/lib/formatters";
import { safeReturnTo } from "@/lib/returnTo";
import type { SupplierAvailability, SupplierStatus } from "@/lib/types/inventory";
import { isSku } from "@/lib/reservationAttempt";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import { Availability, field, Freshness, primaryAction, secondaryAction, surface, WorkspaceHeading } from "@/components/Workspace";

const outcomes: Record<SupplierStatus, string> = {
  AVAILABLE: "Availability reported",
  UNKNOWN: "SKU unknown to supplier",
  UNAVAILABLE: "Supplier unavailable",
  SUPPLIER_UNAVAILABLE: "Supplier unavailable",
  SUPPLIER_TIMEOUT: "Supplier timed out",
  WAREHOUSE_UNAVAILABLE: "Warehouse unavailable",
  WAREHOUSE_TIMEOUT: "Warehouse did not respond",
  INVALID_SUPPLIER_RESPONSE: "Supplier response could not be verified",
  INVALID_REQUEST: "Check the SKU",
  GATEWAY_UNAVAILABLE: "Gateway unavailable",
  GATEWAY_TIMEOUT: "Gateway did not respond",
  INQUIRY_FAILED: "Inquiry failed",
};

function SupplierInquiry({ initialSku, back }: { initialSku: string; back: string }) {
  const [sku, setSku] = useState(initialSku);
  const [lookup, setLookup] = useState<string | null>(null);
  const [result, setResult] = useState<SupplierAvailability | null>(null);
  const [validation, setValidation] = useState(false);
  const [pickerFocused, setPickerFocused] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const revision = useRef(0);
  const invalidate = useCallback(() => { revision.current++; }, []);
  const products = useWarehouseQuery(fetchProducts, true, true, "demo:products");
  const product = products.data?.find((row) => row.sku === sku.trim());
  const needle = sku.trim().toLowerCase();
  const matchingProducts = (products.data ?? [])
    .filter((row) => !needle || `${row.name} ${row.sku}`.toLowerCase().includes(needle))
    .slice(0, 6);
  const showSuggestions = pickerFocused && matchingProducts.length > 0 && !(matchingProducts.length === 1 && matchingProducts[0].sku === sku.trim());

  const inquire = useCallback(async (requested: string) => {
    const normalized = requested.trim();
    if (!isSku(normalized)) {
      setValidation(true);
      return;
    }
    const ticket = ++revision.current;
    setLookup(normalized);
    setResult(null);
    setValidation(false);
    try {
      const supplier = await fetchSupplierAvailability(normalized);
      if (ticket === revision.current) setResult(supplier);
    } catch {
      if (ticket === revision.current) setResult({ sku: normalized, status: "INQUIRY_FAILED" });
    } finally {
      if (ticket === revision.current) setLookup(null);
    }
  }, []);

  useEffect(() => {
    const timer = initialSku ? setTimeout(() => void inquire(initialSku), 0) : undefined;
    return () => {
      clearTimeout(timer);
      invalidate();
    };
  }, [initialSku, inquire, invalidate]);

  function edit(value: string) {
    revision.current++;
    setSku(value);
    setLookup(null);
    setResult(null);
    setValidation(false);
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    void inquire(sku);
  }

  return (
    <div className="space-y-6">
      <WorkspaceHeading title="Supplier availability" description="Check what the supplier reports for an item before following up manually."
        action={<Link href={back} className={secondaryAction}>{back === "/" ? "Back to overview" : back.startsWith("/products") ? "Back to inventory" : "Back"}</Link>} />

      <div className="grid items-start gap-5 sm:grid-cols-2">
        <form onSubmit={submit} className={`${surface} space-y-4 p-5 sm:col-span-2`}>
          <div className="space-y-1.5" onFocus={() => setPickerFocused(true)} onBlur={(event) => {
            if (!event.relatedTarget || !event.currentTarget.contains(event.relatedTarget)) {
              setPickerFocused(false);
              setActiveIndex(-1);
            }
          }}>
            <label htmlFor="supplier-sku" className="block text-sm font-semibold">Product SKU</label>
            <input id="supplier-sku" type="search" value={sku} onChange={(event) => { edit(event.target.value); setActiveIndex(-1); }}
              role="combobox" aria-autocomplete="list" aria-expanded={showSuggestions} aria-controls="supplier-sku-listbox"
              aria-activedescendant={showSuggestions && activeIndex >= 0 ? `supplier-option-${activeIndex}` : undefined}
              onKeyDown={(event) => {
                if (!showSuggestions) return;
                if (event.key === "ArrowDown") {
                  event.preventDefault();
                  setActiveIndex((prev) => (prev + 1) % matchingProducts.length);
                } else if (event.key === "ArrowUp") {
                  event.preventDefault();
                  setActiveIndex((prev) => (prev <= 0 ? matchingProducts.length - 1 : prev - 1));
                } else if (event.key === "Enter" && activeIndex >= 0 && matchingProducts[activeIndex]) {
                  event.preventDefault();
                  const chosen = matchingProducts[activeIndex].sku;
                  edit(chosen);
                  setPickerFocused(false);
                  void inquire(chosen);
                } else if (event.key === "Escape") {
                  setPickerFocused(false);
                }
              }}
              className={field + " mt-1"} autoComplete="off" placeholder="Search product or enter exact SKU"
              aria-describedby={validation ? "supplier-error" : "supplier-help"} aria-invalid={validation} />
            {showSuggestions && (
              <ul id="supplier-sku-listbox" role="listbox" aria-label="Catalog products"
                className="divide-y divide-slate-100 rounded-xl border border-slate-200 bg-white shadow-sm">
                {matchingProducts.map((item, idx) => (
                  <li key={item.sku} id={`supplier-option-${idx}`} role="option" aria-selected={idx === activeIndex}>
                    <button type="button"
                      onClick={() => { edit(item.sku); setPickerFocused(false); void inquire(item.sku); }}
                      className={`flex min-h-11 w-full items-center justify-between gap-3 px-3 py-2 text-left text-sm hover:bg-teal-50 ${idx === activeIndex ? "bg-teal-50" : ""}`}>
                      <span className="min-w-0">
                        <span className="block break-words font-semibold text-slate-900">{item.name}</span>
                        <span className="block font-mono text-xs text-slate-600">{item.sku}</span>
                      </span>
                      <Availability units={item.available} />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
          {validation
            ? <p id="supplier-error" role="alert" className="text-sm text-rose-900">Enter a SKU with 1–64 letters, digits, hyphens or underscores.</p>
            : <p id="supplier-help" className="text-sm text-slate-600">Choose a warehouse item or enter its exact SKU.</p>}
          <button type="submit" className={primaryAction} disabled={lookup !== null}>{lookup ? "Checking…" : "Check availability"}</button>
          <details className="border-t border-slate-200 pt-4 text-sm">
            <summary className="cursor-pointer font-semibold text-slate-700">Supplier demo scenarios</summary>
            <div className="mt-3 flex flex-wrap gap-2">
              {[["ITEM-008", "Available"], ["ITEM-ZERO", "Confirmed zero"], ["ITEM-UNKNOWN", "Unknown SKU"], ["ITEM-OUTAGE", "Outage"], ["ITEM-MALFORMED", "Malformed response"]].map(([item, label]) => (
                <button key={item} type="button" className={secondaryAction} onClick={() => { edit(item); void inquire(item); }}>{label}</button>
              ))}
            </div>
          </details>
        </form>

        <section className={`${surface} space-y-2 p-5`} aria-label="Warehouse stock">
          <h2 className="font-semibold">Warehouse available</h2>
          {product ? (
            <><p className="text-2xl font-semibold tabular-nums">{formatUnits(product.available)} available</p><p className="text-sm text-slate-700">{product.name} · {product.sku}</p></>
          ) : <p className="text-sm text-slate-600">{sku.trim() ? "No balance for this SKU in the loaded catalog." : "Choose a SKU to see its warehouse balance."}</p>}
          <Freshness {...products} />
        </section>

      <section className={`${surface} min-h-40 space-y-2 p-5`} aria-label="Supplier result" aria-live="polite">
        <h2 className="text-lg font-semibold">Supplier reported</h2>
        {lookup && <p className="text-sm text-slate-600">Checking supplier for {lookup}…</p>}
        {!result && !lookup && <p className="text-sm text-slate-600">Run an inquiry to see the supplier’s reported stock.</p>}
        {result && (
          <>
            <p className="text-sm font-semibold text-slate-700">{result.status === "UNKNOWN" ? `Supplier has no listing for ${result.sku}` : `${result.sku} · ${outcomes[result.status]}`}</p>
            {result.status === "AVAILABLE" ? (
              <>
                <p className="text-2xl font-semibold tabular-nums text-slate-950">{formatUnits(result.reportedQuantity)} reported</p>
                <p className="text-sm text-slate-700">{result.supplier || (result.source === "MOCK_SUPPLIER" ? "Demo supplier" : result.source)} · Checked {formatFullDateTime(result.checkedAt)}</p>
                {result.estimatedRestockDays !== undefined && <p className="text-sm text-slate-600">Supplier restock estimate {result.estimatedRestockDays} days</p>}
              </>
            ) : (
              <>
                <p className="text-sm text-slate-700">{result.message ?? "No supplier quantity was confirmed."}</p>
                <button type="button" className={secondaryAction} onClick={() => void inquire(result.sku)}>Check again</button>
              </>
            )}
          </>
        )}
      </section>
      </div>
    </div>
  );
}

export function SuppliersClient() {
  const params = useSearchParams();
  const initialSku = params.get("sku") ?? "";
  return <SupplierInquiry key={initialSku} initialSku={initialSku} back={safeReturnTo(params.get("returnTo"))} />;
}
