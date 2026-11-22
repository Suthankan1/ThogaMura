import type { ReactNode } from "react";
import type { StockRequestStatus } from "@/lib/types/inventory";
import { formatOperationalTime } from "@/lib/formatters";

export const primaryAction = "inline-flex min-h-11 items-center justify-center rounded-lg bg-teal-800 px-4 py-2 text-sm font-semibold text-white hover:bg-teal-900 disabled:opacity-50";
export const secondaryAction = "inline-flex min-h-11 items-center justify-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-semibold text-slate-800 hover:bg-slate-50 disabled:opacity-50";
export const surface = "rounded-xl border border-slate-200 bg-white shadow-xs";

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

const states: Record<StockRequestStatus, { label: string; tone: string }> = {
  ACCEPTED: { label: "To pick", tone: "border-blue-200 bg-blue-50 text-blue-900" },
  REJECTED: { label: "Declined", tone: "border-rose-200 bg-rose-50 text-rose-900" },
  PICKED: { label: "Ready to dispatch", tone: "border-amber-200 bg-amber-50 text-amber-950" },
  DISPATCHED: { label: "Dispatched", tone: "border-teal-200 bg-teal-50 text-teal-950" },
};

export function RequestBadge({ status }: { status: StockRequestStatus }) {
  const state = states[status];
  return <span className={`inline-flex rounded-full border px-2.5 py-1 text-xs font-semibold ${state.tone}`}>{state.label}</span>;
}

export function Freshness({ checkedAt, error, loading }: { checkedAt: Date | null; error?: string | null; loading?: boolean }) {
  const checked = checkedAt && formatOperationalTime(checkedAt);
  return (
    <p className={`text-xs ${error ? "text-amber-900" : "text-slate-600"}`}>
      {error ? (checked ? `Refresh failed · last checked ${checked}` : "Unavailable") :
        loading ? "Checking warehouse…" : checked ? `Checked ${checked}` : "Awaiting first check"}
    </p>
  );
}
