"use client";

import React from "react";
import Link from "next/link";
import { useStockEvents } from "../lib/events/useStockEvents";

interface ConnectionIndicatorProps {
  compact?: boolean;
}

export function ConnectionIndicator({ compact = false }: ConnectionIndicatorProps) {
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
  let compactBg = "bg-slate-100 border-slate-200";

  if (isRefreshing) {
    statusText = "Refreshing feeds...";
    statusBadgeStyle = "bg-teal-50 text-teal-800 border-teal-200";
    dotStyle = "bg-teal-600 animate-pulse";
    compactBg = "bg-teal-50 border-teal-200";
  } else if (isLive) {
    statusText = lastVersion ? `Live (v${lastVersion})` : "Live updates active";
    statusBadgeStyle = "bg-emerald-50 text-emerald-800 border-emerald-200";
    dotStyle = "bg-emerald-500";
    compactBg = "bg-emerald-50 border-emerald-200";
  } else if (sseStatus === "CONNECTED" && upstreamNioStatus !== "CONNECTED") {
    statusText = "Warehouse feed interrupted";
    statusBadgeStyle = "bg-amber-50 text-amber-800 border-amber-200";
    dotStyle = "bg-amber-500 animate-pulse";
    compactBg = "bg-amber-50 border-amber-200";
  } else if (sseStatus === "DISCONNECTED" || sseStatus === "ERROR") {
    statusText = "Live updates disconnected";
    statusBadgeStyle = "bg-rose-50 text-rose-800 border-rose-200";
    dotStyle = "bg-rose-500 animate-ping";
    compactBg = "bg-rose-50 border-rose-200";
  }

  // Mobile compact presentation: safe 36x36px icon indicator linking to /activity
  if (compact) {
    return (
      <Link
        href="/activity"
        aria-label={`Network status: ${statusText}. Tap to view System Status.`}
        title={`${statusText}. Tap to view System Status.`}
        className={`inline-flex h-9 w-9 items-center justify-center rounded-lg border ${compactBg} transition-colors hover:shadow-xs focus:outline-hidden focus:ring-2 focus:ring-teal-600`}
      >
        <span className={`inline-block h-2.5 w-2.5 rounded-full ${dotStyle}`} aria-hidden="true" />
        <span className="sr-only">{statusText}</span>
      </Link>
    );
  }

  return (
    <div className="flex items-center gap-2 text-xs">
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
    </div>
  );
}
