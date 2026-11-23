"use client";

import { secondaryAction } from "./Workspace";

const pageSize = 20;

export function listPage<T>(rows: T[], requestedPage: string | null, selectedIndex = -1) {
  const pages = Math.max(1, Math.ceil(rows.length / pageSize));
  const number = Math.min(pages, Math.max(1, Number.parseInt(requestedPage ?? "", 10) || (selectedIndex >= 0 ? Math.floor(selectedIndex / pageSize) + 1 : 1)));
  return { number, pages, items: rows.slice((number - 1) * pageSize, number * pageSize) };
}

export function updateListParams(values: Record<string, string | null>, replace = false) {
  const params = new URLSearchParams(window.location.search);
  for (const [key, value] of Object.entries(values)) {
    if (value) params.set(key, value);
    else params.delete(key);
  }
  window.history[replace ? "replaceState" : "pushState"](null, "", `${window.location.pathname}${params.size ? `?${params}` : ""}`);
}

export function ListPages({ number, pages, onChange }: { number: number; pages: number; onChange: (page: number) => void }) {
  if (pages <= 1) return null;
  return <nav aria-label="List pages" className="list-pager grid items-center gap-2 text-center border-t border-slate-200 p-4">
    <button className={secondaryAction} disabled={number <= 1} onClick={() => onChange(number - 1)}>Previous</button>
    <span className="text-sm text-slate-700">Page {number} of {pages}</span>
    <button className={secondaryAction} disabled={number >= pages} onClick={() => onChange(number + 1)}>Next</button>
  </nav>;
}
