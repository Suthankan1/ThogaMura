import { Suspense } from "react";
import type { Metadata } from "next";
import { WarehouseQueueClient } from "./WarehouseQueueClient";

export const metadata: Metadata = {
  title: "Warehouse Fulfillment Queue",
  description: "Picker queue executing verified state transitions from ACCEPTED to PICKED to DISPATCHED.",
};

export default function WarehousePage() {
  return (
    <Suspense
      fallback={
        <div className="mx-auto max-w-6xl animate-pulse space-y-4 p-8">
          <div className="h-8 w-48 rounded-lg bg-slate-200" />
          <div className="h-64 rounded-xl border border-slate-200 bg-slate-100" />
        </div>
      }
    >
      <WarehouseQueueClient />
    </Suspense>
  );
}
