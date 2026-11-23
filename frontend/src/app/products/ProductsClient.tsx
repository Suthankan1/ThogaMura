"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { fetchProducts } from "@/lib/api/inventory";
import { listPage, ListPages, updateListParams } from "@/components/ListPages";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import { stockFilters as filters, textAction } from "@/components/Workspace";

import { RecordList } from "@/components/RecordList";

export function ProductsClient() {
  const params = useSearchParams();
  const search = params.get("q") ?? "";
  const filter = filters.find((entry) => entry.key === params.get("filter"))?.key ?? "";
  const inventory = useWarehouseQuery(fetchProducts);
  const needle = search.trim().toLowerCase();
  const rows = (inventory.data ?? []).filter((item) => {
    if (needle && !`${item.name} ${item.sku}`.toLowerCase().includes(needle)) return false;
    if (filter === "in_stock") return item.available >= 4;
    if (filter === "low_stock") return item.available > 0 && item.available <= 3;
    if (filter === "out_of_stock") return item.available === 0;
    return true;
  });
  const page = listPage(rows, params.get("page"));
  const returnTo = `/products${params.size > 0 ? `?${params}` : ""}`;

  return <RecordList title="Inventory" noun="product" count={rows.length} loaded={inventory.data?.length}
    returnTo={returnTo} search={search} prompt="Search product or SKU" label="Availability"
    filter={filter} filterKey="filter" options={filters} query={inventory}>
    {!!rows.length && <table className="records-table" aria-label="Inventory">
      <thead><tr><th>Product</th><th className="number">On hand</th><th className="number">Reserved</th><th className="number">Available</th><th>Action</th></tr></thead>
      <tbody>{page.items.map(item => {
        const out = item.available === 0;
        const destination = out ? "/suppliers" : "/requests/new";
        return <tr key={item.sku}>
          <td><strong>{item.name}</strong><small>{item.sku}</small></td>
          <td className="number desktop-cell">{item.onHand}</td><td className="number desktop-cell">{item.reserved}</td>
          <td className="number available">{item.available}<span className="phone-label text-xs"> available</span>
            {item.available <= 3 && <small className={'stock-note ' + (out ? '!text-rose-800' : '!text-amber-800')}>{out ? "Out of stock" : "Low stock"}</small>}
          </td>
          <td className="row-action"><Link className={textAction} href={destination + '?' + new URLSearchParams({ sku:item.sku, returnTo })}>{out ? "Check supplier" : "Request stock"}</Link></td>
          <td className="stock-breakdown"><details className="phone-stock"><summary>Stock breakdown</summary>{item.onHand} on hand · {item.reserved} reserved</details></td>
        </tr>;
      })}</tbody>
    </table>}
    <ListPages {...page} onChange={next => updateListParams({ page: String(next) })}/>
  </RecordList>;
}
