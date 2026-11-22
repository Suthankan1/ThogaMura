"use client";

import React from "react";
import Link from "next/link";
import { useStockEvents } from "../lib/events/useStockEvents";

export function ConnectionIndicator() {
  const {
    sseStatus,
    upstreamNioStatus,
    isLive,
    isRefreshing,
    lastVersion,
    warningMessage,
    triggerRefresh,
  } = useStockEvents();

  // Determine user-facing text and aria announcements
  let statusText = "Connecting...";
  let statusBadgeStyle = "bg-slate-100 text-slate-700 border-slate-200";
  let dotStyle = "bg-slate-400";

  if (isRefreshing) {
    statusText = "Refreshing feeds...";
    statusBadgeStyle = "bg-teal-50 text-teal-800 border-teal-200";
    dotStyle = "bg-teal-600 animate-pulse";
  } else if (isLive) {
    statusText = lastVersion ? `Live (v${lastVersion})` : "Live updates active";
    statusBadgeStyle = "bg-emerald-50 text-emerald-800 border-emerald-200";
    dotStyle = "bg-emerald-500";
  } else if (sseStatus === "CONNECTED" && upstreamNioStatus !== "CONNECTED") {
    statusText = "Warehouse feed interrupted";
    statusBadgeStyle = "bg-amber-50 text-amber-800 border-amber-200";
    dotStyle = "bg-amber-500 animate-pulse";
  } else if (sseStatus === "DISCONNECTED" || sseStatus === "ERROR") {
    statusText = "Live updates disconnected";
    statusBadgeStyle = "bg-rose-50 text-rose-800 border-rose-200";
    dotStyle = "bg-rose-500 animate-ping";
  }

  return (
    <div className="flex flex-wrap items-center gap-2 text-xs">
      {/* Live accessibility announcement */}
      <span className="sr-only" role="status" aria-live="polite">
        Network status: {statusText}. {warningMessage ? warningMessage : ""}
      </span>

      {/* Visual status pill with accessible text */}
      <Link
        href="/activity"
        className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 font-medium transition-colors hover:shadow-xs focus:outline-hidden focus:ring-2 focus:ring-teal-600 ${statusBadgeStyle}`}
        title={warningMessage || `SSE: ${sseStatus}, Warehouse NIO: ${upstreamNioStatus}`}
      >
        <span className={`inline-block h-2 w-2 rounded-full ${dotStyle}`} aria-hidden="true" />
        <span className="font-semibold">{statusText}</span>
      </Link>

      {/* Manual refresh button for operator reassurance */}
      <button
        type="button"
        onClick={() => void triggerRefresh("MANUAL")}
        disabled={isRefreshing}
        aria-label="Reconcile authoritative data and check real-time connection"
        className="inline-flex items-center rounded-md border border-slate-300 bg-white px-2 py-1 text-xs font-medium text-slate-700 shadow-xs hover:bg-slate-50 focus:outline-hidden focus:ring-2 focus:ring-teal-600 disabled:opacity-50"
      >
        <svg
          className={`mr-1 h-3 w-3 text-slate-500 ${isRefreshing ? "animate-spin text-teal-600" : ""}`}
          fill="none"
          viewBox="0 0 24 24"
          strokeWidth="2"
          stroke="currentColor"
          aria-hidden="true"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0 3.181 3.183a8.25 8.25 0 0 0 13.803-3.7M4.031 9.865a8.25 8.25 0 0 1 13.803-3.7l3.181 3.182m0-4.991v4.99"
          />
        </svg>
        Reconcile
      </button>

      {/* Stale data warning banner when disconnected */}
      {warningMessage && (
        <div
          role="alert"
          className="flex w-full items-center gap-1.5 rounded-md bg-amber-50 px-3 py-1 text-xs text-amber-900 border border-amber-200"
        >
          <svg className="h-4 w-4 shrink-0 text-amber-700" fill="currentColor" viewBox="0 0 20 20">
            <path
              fillRule="evenodd"
              d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 6a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 6zm0 9a1 1 0 100-2 1 1 0 000 2z"
              clipRule="evenodd"
            />
          </svg>
          <span>{warningMessage} Displayed inventory may be outdated.</span>
        </div>
      )}
    </div>
  );
}
