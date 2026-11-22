"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { ApiError, dispatchStockRequest, fetchProducts, fetchStockMovements, fetchStockRequests, pickStockRequest } from "@/lib/api/inventory";
import { formatFullDateTime, formatUnits } from "@/lib/formatters";
import type { StockRequest } from "@/lib/types/inventory";
import { filterMovements, movementCsv } from "@/lib/movementReport";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import { Freshness, primaryAction, RequestBadge, secondaryAction, surface, WorkspaceHeading } from "@/components/Workspace";

const readPicks = () => fetchStockRequests("ACCEPTED");
const readDispatches = () => fetchStockRequests("PICKED");

type Stage = "pick" | "dispatch" | "movements";
const stages: { key: Stage; label: string }[] = [
  { key: "pick", label: "To pick" },
  { key: "dispatch", label: "Ready to dispatch" },
  { key: "movements", label: "Movements" },
];

export function WarehouseQueueClient() {
  const params = useSearchParams();
  const target = params.get("requestId");
  const preferredStage = params.get("stage")?.toLowerCase();
  // Selection is scoped to the incoming URL, so direct links can change the active queue.
  const routeKey = params.toString();
  const [selection, select] = useState<{ route: string; stage: Stage } | null>(null);
  const stage = selection?.route === routeKey ? selection.stage : preferredStage === "dispatch" || preferredStage === "movements" ? preferredStage : "pick";
  const picks = useWarehouseQuery(readPicks);
  const dispatches = useWarehouseQuery(readDispatches);
  const catalog = useWarehouseQuery(fetchProducts);
  const movements = useWarehouseQuery(fetchStockMovements, stage === "movements");
  const [movementSku, setMovementSku] = useState("");
  const [movementFrom, setMovementFrom] = useState("");
  const [movementThrough, setMovementThrough] = useState("");
  const filteredMovements = filterMovements(movements.data ?? [], movementSku, movementFrom, movementThrough);
  const [review, setReview] = useState<StockRequest | null>(null);
  const [working, setWorking] = useState<string | null>(null);
  const actionInFlight = useRef(false);
  const [feedback, setFeedback] = useState<{
    id: string;
    text: string;
    error?: boolean;
    retry?: { request: StockRequest; action: "pick" | "dispatch" };
  } | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const dialogTrigger = useRef<HTMLElement | null>(null);
  const targetRef = useRef<HTMLLIElement>(null);
  const query = stage === "pick" ? picks : dispatches;
  const rows = query.data ?? [];
  const hasTarget = !!target && rows.some((row) => row.requestId === target);

  useEffect(() => {
    if (hasTarget) {
      targetRef.current?.scrollIntoView({ block: "nearest" });
      targetRef.current?.focus({ preventScroll: true });
    }
  }, [hasTarget, target]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!review || !dialog) return;
    dialog.showModal();
    return () => {
      dialog.close();
      dialogTrigger.current?.focus();
    };
  }, [review]);

  async function transition(request: StockRequest, action: "pick" | "dispatch") {
    if (actionInFlight.current) return;
    actionInFlight.current = true;
    setWorking(request.requestId);
    setFeedback(null);
    setReview(null);
    try {
      if (action === "pick") await pickStockRequest(request.requestId);
      else await dispatchStockRequest(request.requestId);
      setFeedback({ id: request.requestId, text: action === "pick" ? "Pickup confirmed. The request is ready to dispatch." : "Dispatch confirmed. The stock movement is recorded." });
      await Promise.all([picks.refresh(), dispatches.refresh(), catalog.refresh(), movements.refresh()]);
    } catch (error) {
      const confirmed = error instanceof ApiError && error.status < 500;
      setFeedback({
        id: request.requestId,
        text: confirmed ? error.message : "Warehouse response unconfirmed. Retrying this action will use the same command reference.",
        error: true,
        retry: confirmed ? undefined : { request, action },
      });
      if (confirmed) await Promise.all([picks.refresh(), dispatches.refresh()]);
    } finally {
      actionInFlight.current = false;
      setWorking(null);
    }
  }

  function nameFor(sku: string) {
    return catalog.data?.find((product) => product.sku === sku)?.name ?? sku;
  }

  function exportMovements() {
    const url = URL.createObjectURL(new Blob([movementCsv(filteredMovements)], { type: "text/csv;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "thogamura-movements.csv";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  return (
    <div className="space-y-6">
      <WorkspaceHeading title="Warehouse queue" description="Pick accepted requests and confirm shipments from the warehouse."
        action={<button className={secondaryAction} onClick={() => void (stage === "movements" ? movements.refresh() : query.refresh())}>Refresh view</button>} />

      <nav aria-label="Warehouse views" className="flex flex-wrap gap-2">
        {stages.map((item) => (
          <button key={item.key} type="button" aria-pressed={stage === item.key}
            onClick={() => select({ route: routeKey, stage: item.key })}
            className={stage === item.key ? primaryAction : secondaryAction}>
            {item.label}
          </button>
        ))}
      </nav>

      {feedback && (
        <p role={feedback.error ? "alert" : "status"} className={`rounded-lg border p-4 text-sm ${feedback.error ? "border-amber-200 bg-amber-50 text-amber-950" : "border-teal-200 bg-teal-50 text-teal-950"}`}>
          {feedback.text}{" "}
          <Link className="font-semibold underline" href={`/requests?ref=${encodeURIComponent(feedback.id)}`}>View request</Link>
          {feedback.retry && (
            <button type="button" disabled={working !== null} className="ml-3 font-semibold underline disabled:opacity-50"
              onClick={() => {
                if (feedback.retry) void transition(feedback.retry.request, feedback.retry.action);
              }}>
              Retry same action
            </button>
          )}
        </p>
      )}

      {stage === "movements" ? (
        <section className={surface} aria-labelledby="movements-title">
          <div className="border-b border-slate-200 p-4 sm:p-5">
            <h2 id="movements-title" className="font-semibold text-slate-950">Stock movements</h2>
            <Freshness {...movements} />
            {movements.error && <p role="alert" className="mt-2 text-sm text-rose-900">{movements.error}</p>}
          </div>
          {movements.data && (
            <div className="flex flex-wrap items-end gap-3 border-b border-slate-200 p-4 text-sm">
              <label className="font-semibold">SKU
                <input value={movementSku} onChange={(event) => setMovementSku(event.target.value)} className="mt-1 block min-h-11 max-w-48 rounded-lg border border-slate-300 px-3 font-normal" />
              </label>
              <label className="font-semibold">From (UTC)
                <input type="date" value={movementFrom} onChange={(event) => setMovementFrom(event.target.value)} className="mt-1 block min-h-11 rounded-lg border border-slate-300 px-3 font-normal" />
              </label>
              <label className="font-semibold">Through (UTC)
                <input type="date" min={movementFrom || undefined} value={movementThrough} onChange={(event) => setMovementThrough(event.target.value)} className="mt-1 block min-h-11 rounded-lg border border-slate-300 px-3 font-normal" />
              </label>
              <button type="button" className={secondaryAction} disabled={filteredMovements.length === 0} onClick={exportMovements}>Export loaded rows</button>
            </div>
          )}
          {filteredMovements.length ? (
            <ul className="divide-y divide-slate-100">
              {[...filteredMovements].reverse().map((movement) => (
                <li key={movement.id} className="grid grid-cols-[1fr_auto] gap-3 p-4 text-sm sm:p-5">
                  <div className="min-w-0">
                    <p className="font-semibold">{nameFor(movement.sku)}</p>
                    <p className="text-xs text-slate-600">{movement.sku} · {formatFullDateTime(movement.occurredAt)}</p>
                    <Link className="mt-1 inline-block break-all text-xs font-semibold text-teal-800 underline" href={`/requests?ref=${encodeURIComponent(movement.stockRequestId)}`}>Request {movement.stockRequestId.slice(0, 8)}</Link>
                  </div>
                  <p className="text-right font-semibold tabular-nums text-rose-900">{formatUnits(movement.quantityDelta)}<span className="block text-xs font-normal text-slate-600">Dispatch</span></p>
                </li>
              ))}
            </ul>
          ) : movements.data && <p className="p-5 text-sm text-slate-600">No loaded movements match the filters.</p>}
          {movements.data?.length === 500 && <p className="border-t border-slate-200 p-4 text-sm text-slate-600">Showing the warehouse’s bounded movement list.</p>}
        </section>
      ) : (
        <section className={surface} aria-labelledby="queue-title">
          <div className="border-b border-slate-200 p-4 sm:p-5">
            <h2 id="queue-title" className="font-semibold text-slate-950">{stage === "pick" ? "Accepted requests" : "Picked requests"}</h2>
            <Freshness {...query} />
            {query.error && <p role="alert" className="mt-2 text-sm text-rose-900">{query.error}</p>}
          </div>
          {target && !hasTarget && !query.loading && query.data && (
            <p className="border-b border-slate-200 p-4 text-sm text-slate-700">
              This reference is absent from the loaded stage. <Link className="font-semibold text-teal-800 underline" href={`/requests?ref=${encodeURIComponent(target)}`}>Check request history</Link>
            </p>
          )}
          {rows.length ? (
            <ol className="divide-y divide-slate-100">
              {rows.map((item) => (
                <li key={item.requestId} ref={item.requestId === target ? targetRef : undefined} tabIndex={item.requestId === target ? -1 : undefined}
                  className={`flex flex-wrap items-center justify-between gap-4 p-4 sm:p-5 ${item.requestId === target ? "bg-teal-50" : ""}`}>
                  <div className="min-w-0 flex-1 sm:min-w-48">
                    <p className="font-semibold text-slate-950">{nameFor(item.sku)}</p>
                    <p className="mt-1 text-xs text-slate-600">{item.sku} · {formatFullDateTime(item.createdAt)}</p>
                    <Link href={`/requests?ref=${encodeURIComponent(item.requestId)}`} className="mt-1 inline-block text-xs font-semibold text-teal-800 underline">
                      Request {item.requestId.slice(0, 8)}
                    </Link>
                  </div>
                  <div>
                    <p className="mb-1 text-sm font-semibold tabular-nums">{formatUnits(item.quantity)}</p>
                    <RequestBadge status={item.status} />
                  </div>
                  <button type="button" className={primaryAction} disabled={working !== null}
                    onClick={(event) => {
                      if (stage === "pick") void transition(item, "pick");
                      else {
                        dialogTrigger.current = event.currentTarget;
                        setReview(item);
                      }
                    }}>
                    {working === item.requestId ? "Confirming…" : stage === "pick" ? "Mark picked" : "Review dispatch"}
                  </button>
                </li>
              ))}
            </ol>
          ) : query.data && <p className="p-5 text-sm text-slate-600">No requests in this stage.</p>}
          {rows.length === 500 && <p className="border-t border-slate-200 p-4 text-sm text-slate-600">Showing the first 500 requests in this stage.</p>}
        </section>
      )}

      <dialog ref={dialogRef} onCancel={(event) => { event.preventDefault(); setReview(null); }}
        aria-labelledby="dispatch-title" className="fixed inset-0 m-auto w-[calc(100%-2rem)] max-w-md rounded-xl border border-slate-200 bg-white p-6 shadow-xl backdrop:bg-slate-950/60">
        {review && (
          <>
            <h2 id="dispatch-title" className="text-lg font-bold text-slate-950">Confirm dispatch</h2>
            <p className="mt-2 text-sm text-slate-700">Confirm that {formatUnits(review.quantity)} of {nameFor(review.sku)} have left the warehouse.</p>
            <p className="mt-2 break-all text-xs text-slate-600">Reference {review.requestId}</p>
            <div className="mt-6 flex flex-wrap justify-end gap-2">
              <button type="button" autoFocus className={secondaryAction} onClick={() => setReview(null)}>Cancel</button>
              <button type="button" className={primaryAction} onClick={() => void transition(review, "dispatch")}>Confirm dispatch</button>
            </div>
          </>
        )}
      </dialog>
    </div>
  );
}
