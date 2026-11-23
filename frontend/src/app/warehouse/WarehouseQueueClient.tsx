"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { ApiError, dispatchStockRequest, fetchProducts, fetchStockMovements, fetchStockRequests, pickStockRequest } from "@/lib/api/inventory";
import { ConfirmAction } from "@/components/ConfirmAction";
import { ListPages, listPage, updateListParams } from "@/components/ListPages";
import { formatAge, formatFullDateTime, formatUnits } from "@/lib/formatters";
import type { StockRequest } from "@/lib/types/inventory";
import { downloadCsv, filterMovements, movementCsv } from "@/lib/movementReport";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import { Freshness, primaryAction, RequestBadge, secondaryAction, surface, textAction, WorkspaceHeading } from "@/components/Workspace";

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
  const stage = preferredStage === "dispatch" || preferredStage === "movements" ? preferredStage : "pick";
  const search = params.get("q") ?? "";
  const picks = useWarehouseQuery(readPicks);
  const dispatches = useWarehouseQuery(readDispatches);
  const catalog = useWarehouseQuery(fetchProducts);
  const movements = useWarehouseQuery(fetchStockMovements, stage === "movements");
  const [movementSku, setMovementSku] = useState("");
  const [movementFrom, setMovementFrom] = useState("");
  const [movementThrough, setMovementThrough] = useState("");
  const filteredMovements = filterMovements(movements.data ?? [], movementSku, movementFrom, movementThrough)
    .filter((row) => !target || target === row.stockRequestId).reverse();
  const movementPage = listPage(filteredMovements, params.get("page"));
  const [review, setReview] = useState<StockRequest | null>(null);
  const [working, setWorking] = useState<string | null>(null);
  const actionInFlight = useRef(false);
  const [feedback, setFeedback] = useState<{
    id: string;
    text: string;
    error?: boolean;
    retry?: { request: StockRequest; action: "pick" | "dispatch" };
  } | null>(null);
  const targetRef = useRef<HTMLLIElement>(null);
  const query = stage === "pick" ? picks : dispatches;
  const loaded = query.data ?? [];
  const hasTarget = stage !== "movements" && !!target && loaded.some((row) => row.requestId === target);
  const rows = loaded.filter((row) => !search || `${row.sku} ${row.requestId} ${nameFor(row.sku)}`.toLowerCase().includes(search.toLowerCase()));
  const targetIndex = hasTarget ? rows.findIndex((row) => row.requestId === target) : -1;
  const queuePage = listPage(rows, params.get("page"), targetIndex);

  useEffect(() => {
    if (hasTarget) {
      targetRef.current?.scrollIntoView({ block: "nearest" });
      targetRef.current?.focus({ preventScroll: true });
    }
  }, [hasTarget, target]);

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

  return (
    <div className="space-y-6">
      <WorkspaceHeading title="Warehouse queue" description="Pick accepted requests and confirm shipments from the warehouse."
        action={<button className={secondaryAction} onClick={() => void (stage === "movements" ? movements.refresh() : query.refresh())}>Refresh view</button>} />

      <nav aria-label="Warehouse views" className="flex flex-wrap gap-2">
        {stages.map((item) => (
          <button key={item.key} type="button" aria-pressed={stage === item.key}
            onClick={() => updateListParams({ stage: item.key, page: null, q: null, requestId: null })}
            className={`${secondaryAction} ${stage === item.key ? "border-teal-600 bg-teal-50 text-teal-950" : ""}`}>
            {item.label}
          </button>
        ))}
      </nav>

      {feedback && (
        <p role={feedback.error ? "alert" : "status"} className={`rounded-lg border p-4 text-sm ${feedback.error ? "border-amber-200 bg-amber-50 text-amber-950" : "border-teal-200 bg-teal-50 text-teal-950"}`}>
          {feedback.text}{" "}
          <Link className={textAction} href={`/requests?ref=${encodeURIComponent(feedback.id)}`}>View request</Link>
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
                <input value={movementSku} onChange={(event) => { setMovementSku(event.target.value); if (params.has("page")) updateListParams({ page: null }); }} className="mt-1 block min-h-11 max-w-48 rounded-lg border border-slate-300 px-3 font-normal" />
              </label>
              <label className="font-semibold">From date
                <input type="date" value={movementFrom} onChange={(event) => { setMovementFrom(event.target.value); if (params.has("page")) updateListParams({ page: null }); }} className="mt-1 block min-h-11 rounded-lg border border-slate-300 px-3 font-normal" />
              </label>
              <label className="font-semibold">Through date
                <input type="date" min={movementFrom || undefined} value={movementThrough} onChange={(event) => { setMovementThrough(event.target.value); if (params.has("page")) updateListParams({ page: null }); }} className="mt-1 block min-h-11 rounded-lg border border-slate-300 px-3 font-normal" />
              </label>
              <button type="button" className={secondaryAction} disabled={filteredMovements.length === 0} onClick={() => downloadCsv("thogamura-movements.csv", movementCsv(filteredMovements))}>Export loaded rows</button>
            </div>
          )}
          {target && <div className="border-b border-slate-200 p-4 text-sm">
            <p className="break-all">Movements for request {target}</p>
            <button className={textAction} onClick={() => updateListParams({ requestId: null, page: null })}>Show all movements</button>
          </div>}
          {filteredMovements.length ? (
            <ul className="divide-y divide-slate-100">
              {movementPage.items.map((movement) => (
                <li key={movement.id} className="grid grid-cols-[1fr_auto] gap-3 p-4 text-sm sm:p-5">
                  <div className="min-w-0">
                    <p className="font-semibold">{nameFor(movement.sku)}</p>
                    <p className="text-xs text-slate-600">{movement.sku} · {formatFullDateTime(movement.occurredAt)}</p>
                    <Link className={textAction} href={`/requests?ref=${encodeURIComponent(movement.stockRequestId)}`}>Request {movement.stockRequestId.slice(0, 8)}</Link>
                  </div>
                  <p className="text-right font-semibold tabular-nums text-rose-900">{formatUnits(movement.quantityDelta)}<span className="block text-xs font-normal text-slate-600">Dispatch</span></p>
                </li>
              ))}
            </ul>
          ) : movements.data && <p className="p-5 text-sm text-slate-600">No movements match this selection in the demo ledger.</p>}
          <ListPages {...movementPage} onChange={(next) => updateListParams({ page: String(next) })} />
          {movements.data?.length === 500 && <p className="border-t border-slate-200 p-4 text-sm text-slate-600">Showing the warehouse’s bounded movement list.</p>}
        </section>
      ) : (
        <section className={surface} aria-labelledby="queue-title">
          <div className="border-b border-slate-200 p-4 sm:p-5">
            <h2 id="queue-title" className="font-semibold text-slate-950">{stage === "pick" ? "Accepted requests" : "Picked requests"}</h2>
            <Freshness {...query} />
            <form className="mt-3 grid items-end gap-2 sm:grid-cols-[minmax(0,1fr)_auto]" onSubmit={(event) => {
              event.preventDefault();
              const data = new FormData(event.currentTarget);
              updateListParams({ q: String(data.get("q")).trim(), page: null, requestId: null });
            }}>
              <label className="min-w-0 text-sm font-semibold">Product, SKU, or reference
                <input key={search} name="q" type="search" defaultValue={search} className="mt-1 block min-h-11 w-full rounded-lg border border-slate-300 px-3 font-normal" />
              </label>
              <button className={`${secondaryAction} justify-self-start`}>Search queue</button>
            </form>
            {query.error && <p role="alert" className="mt-2 text-sm text-rose-900">{query.error}</p>}
          </div>
          {target && !hasTarget && !query.loading && query.data && (
            <p className="border-b border-slate-200 p-4 text-sm text-slate-700">
              This reference is absent from the loaded stage. <Link className="font-semibold text-teal-800 underline" href={`/requests?ref=${encodeURIComponent(target)}`}>Check request history</Link>
            </p>
          )}
          {rows.length ? (
            <ol className="divide-y divide-slate-100">
              {queuePage.items.map((item) => (
                <li key={item.requestId} ref={item.requestId === target ? targetRef : undefined} tabIndex={item.requestId === target ? -1 : undefined}
                  className={`grid items-center gap-3 p-4 sm:grid-cols-[minmax(0,1fr)_auto_auto] sm:p-5 ${item.requestId === target ? "bg-teal-50" : ""}`}>
                  <div className="min-w-0">
                    <p className="font-semibold text-slate-950">{nameFor(item.sku)}</p>
                    <p className="mt-1 text-xs text-slate-600">{item.sku} · {formatFullDateTime(item.createdAt)}</p>
                    <Link href={`/requests?ref=${encodeURIComponent(item.requestId)}`} className={`${textAction} break-all`}>
                      Request {item.requestId}
                    </Link>
                  </div>
                  <div>
                    <p className="mb-1 text-sm font-semibold tabular-nums">{formatUnits(item.quantity)}</p>
                    <RequestBadge status={item.status} />
                    <p className="mt-1 text-sm text-amber-900">Requested {formatAge(item.createdAt)} ago</p>
                  </div>
                  <button type="button" className={primaryAction} disabled={working !== null}
                    onClick={() => {
                      if (stage === "pick") void transition(item, "pick");
                      else setReview(item);
                    }}>
                    {working === item.requestId ? "Confirming…" : stage === "pick" ? "Mark picked" : "Review dispatch"}
                  </button>
                </li>
              ))}
            </ol>
          ) : query.data && <p className="p-5 text-sm text-slate-600">{search ? "No loaded requests match this search." : stage === "dispatch" ? "No requests ready to dispatch." : "No requests waiting to pick."}</p>}
          <ListPages {...queuePage} onChange={(next) => updateListParams({ page: String(next) })} />
          {loaded.length === 500 && <p className="border-t border-slate-200 p-4 text-sm text-slate-600">Showing the first 500 requests in this stage.</p>}
        </section>
      )}

      {review && <ConfirmAction title="Confirm dispatch" action="Confirm dispatch"
        description={`Confirm that ${formatUnits(review.quantity)} of ${nameFor(review.sku)} have left the warehouse. This records a stock movement for request ${review.requestId}.`}
        onClose={() => setReview(null)} onConfirm={() => void transition(review, "dispatch")} />}
    </div>
  );
}
