import { Suspense } from "react";
import type { Metadata } from "next";
import { SuppliersClient } from "./SuppliersClient";

export const metadata: Metadata = {
  title: "Advisory Supplier Availability",
  description: "Query independent supplier HTTP simulator for out-of-stock items via Member 2 Java URL networking.",
};

export default function SuppliersPage() {
  return (
    <Suspense
      fallback={
        <div className="mx-auto max-w-3xl animate-pulse space-y-4 p-8">
          <div className="h-8 w-48 rounded-lg bg-slate-200" />
          <div className="h-64 rounded-xl border border-slate-200 bg-slate-100" />
        </div>
      }
    >
      <SuppliersClient />
    </Suspense>
  );
}
