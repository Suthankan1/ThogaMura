import { Suspense } from "react";
import type { Metadata } from "next";
import { NewRequestClient } from "./NewRequestClient";

export const metadata: Metadata = {
  title: "New Stock Request",
  description: "Submit an atomic, idempotent stock reservation request to the warehouse network server.",
};

export default function NewRequestPage() {
  return (
    <Suspense
      fallback={
        <div className="mx-auto max-w-2xl animate-pulse space-y-4 p-8">
          <div className="h-8 w-48 rounded-lg bg-slate-200" />
          <div className="h-64 rounded-xl border border-slate-200 bg-slate-100" />
        </div>
      }
    >
      <NewRequestClient />
    </Suspense>
  );
}
