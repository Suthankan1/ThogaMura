"use client";

import Link from "next/link";
import { useEffect, useRef, type ReactNode } from "react";
import { RefreshCw, Search } from "lucide-react";
import { field, primaryAction, textAction } from "./Workspace";
import { updateListParams } from "./ListPages";
import { formatOperationalTime } from "@/lib/formatters";
import "./records.css";

interface Props {
  title: string;
  noun: string;
  count: number;
  loaded: number | undefined;
  returnTo: string;
  search: string;
  prompt: string;
  filter: string;
  filterKey: string;
  label: string;
  options: { key: string; label: string }[];
  query: { loading: boolean; error: string | null; checkedAt: Date | null; refresh: () => Promise<void> };
  sortControl?: ReactNode;
  banner?: ReactNode;
  children: ReactNode;
}

export function RecordList({ title, noun, count, loaded, returnTo, search, prompt, filter, filterKey, label, options, query, sortControl, banner, children }: Props) {
  const input = useRef<HTMLInputElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => {
    if (input.current) input.current.value = search;
    return () => clearTimeout(timer.current);
  }, [search]);
  useEffect(() => {
    const cancel = () => clearTimeout(timer.current);
    window.addEventListener("popstate", cancel);
    return () => window.removeEventListener("popstate", cancel);
  }, []);
  function apply(nextFilter = filter, replace = true) {
    clearTimeout(timer.current);
    updateListParams({ q: input.current?.value.trim() || null, [filterKey]: nextFilter === options[0].key ? null : nextFilter, page: null }, replace);
  }
  const filtered = Boolean(search || filter !== options[0].key);
  return <div className="records">
    <header className="records-heading">
      <h1 id="records-title" tabIndex={-1}>{title}</h1>
      {loaded !== undefined && <span>{count} {loaded >= 500 ? "loaded " : ""}{noun}{count !== 1 && "s"}</span>}
      {sortControl && <div className="ml-auto flex items-center">{sortControl}</div>}
      <Link className={`${primaryAction}${sortControl ? " !ml-0" : ""}`} href={'/requests/new?returnTo=' + encodeURIComponent(returnTo)}>New request</Link>
    </header>
    <section className="work-surface" aria-labelledby="records-title">
      <form className="records-tools" onSubmit={event => { event.preventDefault(); apply(); }}>
        <label className="relative"><Search size={17} aria-hidden className="absolute left-3 top-3.5 text-slate-500"/>
          <input ref={input} defaultValue={search} type="search" aria-label={prompt} placeholder={prompt} className={field + " pl-9"}
            onChange={() => { clearTimeout(timer.current); timer.current = setTimeout(() => apply(), 250); }}/>
        </label>
        <select aria-label={label} value={filter} className={field} onChange={event => apply(event.target.value, false)}>
          {options.map(option => <option key={option.key} value={option.key}>{option.label}</option>)}
        </select>
        <button type="button" className="records-refresh" disabled={query.loading} aria-describedby="records-fresh" onClick={() => void query.refresh()}>
          <RefreshCw size={16} aria-hidden/> {query.error ? "Retry" : "Refresh"}
        </button>
        <span id="records-fresh" className="sr-only">{query.checkedAt ? "Last successful refresh " + formatOperationalTime(query.checkedAt) : "Awaiting first refresh"}</span>
      </form>
      {query.error && <p role="alert" className="records-message text-rose-900">{query.error} {query.checkedAt && "Showing last confirmed data."}</p>}
      {banner}
      {loaded === undefined && query.loading && <p role="status" className="records-message">Loading {noun}s…</p>}
      {loaded !== undefined && count === 0 && !query.loading && <div className="records-message">
        {filtered ? <>No matching {noun}s. <button className={textAction} onClick={() => {
          if (input.current) input.current.value = "";
          apply(options[0].key, false);
        }}>Clear filters</button></> : 'No ' + noun + 's yet.'}
      </div>}
      {children}
    </section>
  </div>;
}
