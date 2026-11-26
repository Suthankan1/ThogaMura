"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { fetchProducts } from "@/lib/api/inventory";
import { listPage, ListPages, updateListParams } from "@/components/ListPages";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import { stockFilters as filters, field, textAction } from "@/components/Workspace";

import { RecordList } from "@/components/RecordList";

const sortOptions = [
  { key: "", label: "SKU (A–Z)" },
  { key: "low", label: "Lowest available" },
  { key: "reserved", label: "Highest reserved" },
];

export function ProductsClient() {
  const params = useSearchParams();
  const search = params.get("q") ?? "";
  const filter = filters.find((entry) => entry.key === params.get("filter"))?.key ?? "";
  const sort = sortOptions.find((entry) => entry.key === params.get("sort"))?.key ?? "";
  const inventory = useWarehouseQuery(fetchProducts, true, true, "demo:products");
  const needle = search.trim().toLowerCase();
  const filtered = (inventory.data ?? []).filter((item) => {
    if (needle && !`${item.name} ${item.sku}`.toLowerCase().includes(needle)) return false;
    if (filter === "in_stock") return item.available >= 4;
    if (filter === "low_stock") return item.available > 0 && item.available <= 3;
    if (filter === "out_of_stock") return item.available === 0;
    return true;
  });
  const rows = [...filtered].sort((a, b) => {
    if (sort === "low") return a.available - b.available || a.sku.localeCompare(b.sku);
    if (sort === "reserved") return b.reserved - a.reserved || a.sku.localeCompare(b.sku);
    return a.sku.localeCompare(b.sku);
  });
  const page = listPage(rows, params.get("page"));
  const returnTo = `/products${params.size > 0 ? `?${params}` : ""}`;

  return <RecordList title="Inventory" noun="product" count={rows.length} loaded={inventory.data?.length}
    returnTo={returnTo} search={search} prompt="Search product or SKU" label="Availability"
    filter={filter} filterKey="filter" options={filters} query={inventory}
    sortControl={
      <label className="flex items-center gap-2 text-xs font-semibold text-slate-600">
        <span>Sort by</span>
        <select aria-label="Sort products" value={sort} className={field + " !min-h-9 !w-auto !py-1 text-xs"}
          onChange={(event) => updateListParams({ sort: event.target.value || null, page: null }, false)}>
          {sortOptions.map((option) => <option key={option.key} value={option.key}>{option.label}</option>)}
        </select>
      </label>
    }>
    {!!rows.length && <table className="records-table" aria-label="Inventory">
      <thead><tr><th>Product</th><th className="number">On hand</th><th className="number">Reserved</th><th className="number">Available</th><th>Action</th></tr></thead>
      <tbody>{page.items.map(item => {
        const out = item.available === 0;
        const destination = out ? "/suppliers" : "/requests/new";
        return <tr key={item.sku}>
          <td><strong>{item.name}</strong><small>{item.sku}</small></td>
          <td className="number desktop-cell">{item.onHand}</td>
          <td className="number desktop-cell whitespace-nowrap">
            {item.reserved}
            {item.reserved > 0 && (
              <Link className="ml-1.5 text-[11px] font-semibold text-teal-800 underline underline-offset-2" title="View active holds for this SKU" href={`/requests?status=ACCEPTED&q=${encodeURIComponent(item.sku)}`}>
                (holds)
              </Link>
            )}
          </td>
          <td className="number available">{item.available}<span className="phone-label text-xs"> available</span>
            {item.available <= 3 && <small className={'stock-note ' + (out ? '!text-rose-800' : '!text-amber-800')}>{out ? "Out of stock" : "Low stock"}</small>}
          </td>
          <td className="row-action"><Link className={textAction} href={destination + '?' + new URLSearchParams({ sku:item.sku, returnTo })}>{out ? "Check supplier" : "Request stock"}</Link></td>
          <td className="stock-breakdown"><details className="phone-stock"><summary>Stock breakdown</summary>{item.onHand} on hand · {item.reserved} reserved{item.reserved > 0 && <> · <Link className="underline text-teal-800" href={`/requests?status=ACCEPTED&q=${encodeURIComponent(item.sku)}`}>View holds</Link></>}</details></td>
        </tr>;
      })}</tbody>
    </table>}
    <ListPages {...page} onChange={next => updateListParams({ page: String(next) })}/>
  </RecordList>;
}
