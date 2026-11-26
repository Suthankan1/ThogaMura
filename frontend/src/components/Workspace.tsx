import type { ReactNode } from "react";
import type { Status } from "@/lib/api/workspace";
import { formatOperationalTime } from "@/lib/formatters";

export const primaryAction = "inline-flex min-h-11 items-center justify-center text-center leading-5 gap-2 rounded-xl bg-teal-800 motion-safe:transition-colors duration-120 active:bg-teal-950 px-4 py-2 text-sm font-semibold text-white hover:bg-teal-900 disabled:opacity-50";
export const secondaryAction = "inline-flex min-h-11 items-center justify-center text-center leading-5 gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm font-semibold text-slate-800 hover:bg-slate-50 disabled:opacity-50";
export const destructiveAction = "inline-flex min-h-11 items-center justify-center text-center leading-5 gap-2 rounded-xl bg-rose-800 px-4 py-2 text-sm font-semibold text-white hover:bg-rose-900 disabled:opacity-50";
export const textAction = "inline-flex min-h-11 items-center text-sm font-semibold text-teal-800 underline underline-offset-4 hover:text-teal-950";
export const field = "block min-h-11 w-full min-w-0 rounded-xl border border-slate-300 bg-white px-3 py-2 text-sm font-normal text-slate-950";
export const surface = "work-surface";

export function WorkspaceHeading({ title, description, action }: { title: string; description: string; action?: ReactNode }) {
  return (
    <header className="flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-3xl font-bold tracking-tight text-slate-950 sm:text-4xl">{title}</h1>
        <p className="mt-2 max-w-prose text-sm text-slate-600">{description}</p>
      </div>
      {action}
    </header>
  );
}

export const requestStates: Record<Status, { label: string; tone: string }> = {
  ACCEPTED: { label: "To pick", tone: "border-blue-200 bg-blue-50 text-blue-900" },
  REJECTED: { label: "Declined", tone: "border-rose-200 bg-rose-50 text-rose-900" },
  PICKED: { label: "Ready to dispatch", tone: "border-amber-200 bg-amber-50 text-amber-950" },
  CANCELLED: { label: "Cancelled", tone: "border-slate-200 bg-slate-100 text-slate-800" },
  DISPATCHED: { label: "Dispatched", tone: "border-teal-200 bg-teal-50 text-teal-950" },
};

export function RequestBadge({ status }: { status: Status }) {
  const state = requestStates[status];
  return <span className={`inline-flex rounded-full border px-2.5 py-1 text-xs font-semibold ${state.tone}`}>{state.label}</span>;
}

export function Freshness({ checkedAt, error, loading }: { checkedAt: Date | null; error?: string | null; loading?: boolean }) {
  const checked = checkedAt && formatOperationalTime(checkedAt);
  return (
    <p className={`text-xs ${error ? "text-amber-900" : "text-slate-600"}`}>
      {error ? (checked ? `Showing last confirmed data · checked ${checked}` : "Unavailable") :
        loading ? "Checking warehouse…" : checked ? `Checked ${checked}` : "Awaiting first check"}
    </p>
  );
}


export function Field({ children, name, className = "" }: { children: ReactNode; name: string; className?: string }) {
  return <label className={`block min-w-0 space-y-1 text-sm font-semibold text-slate-700 ${className}`}><span>{name}</span>{children}</label>;
}

export function Warning({ children }: { children: ReactNode }) {
  return <div role="alert" className="rounded-xl border border-amber-300 bg-amber-50 p-3 text-sm text-amber-950">{children}</div>;
}

export const stockFilters = [
  { key: "", label: "All stock" },
  { key: "in_stock", label: "4+ available" },
  { key: "low_stock", label: "Low stock" },
  { key: "out_of_stock", label: "Out of stock" },
];

export function Availability({ units }: { units: number }) {
  const tone = units === 0 ? "bg-rose-50 text-rose-900" : units <= 3 ? "bg-amber-50 text-amber-950" : "bg-teal-50 text-teal-950";
  return <span className={`inline-flex rounded-xl px-2.5 py-1 font-semibold tabular-nums ${tone}`}>
    {new Intl.NumberFormat("en").format(units)} available{units === 0 ? " · Out of stock" : units <= 3 ? " · Low stock" : ""}
  </span>;
}

export function StockAllocationBar({ onHand, reserved, available }: { onHand: number; reserved: number; available: number }) {
  const total = Math.max(onHand, reserved + available, 1);
  const availPct = Math.round((Math.max(available, 0) / total) * 100);
  const resPct = Math.min(100 - availPct, Math.round((Math.max(reserved, 0) / total) * 100));
  return (
    <div className="stock-alloc-track" aria-hidden="true" title={`${available} available, ${reserved} reserved of ${onHand} on hand`}>
      {availPct > 0 && <div className="stock-alloc-available" style={{ width: `${availPct}%` }} />}
      {resPct > 0 && <div className="stock-alloc-reserved" style={{ width: `${resPct}%` }} />}
    </div>
  );
}

export function SkeletonRows({ rows = 3 }: { rows?: number }) {
  return (
    <div className="space-y-3 p-4" aria-hidden="true">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="skeleton-shimmer h-10 rounded-xl" />
      ))}
    </div>
  );
}
