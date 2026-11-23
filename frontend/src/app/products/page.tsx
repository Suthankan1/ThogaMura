import { Suspense } from "react";
import type { Metadata } from "next";
import { ProductsClient } from "./ProductsClient";

export const metadata: Metadata = {
  title: "Product Catalog",
  description: "Authoritative product stock levels verified through real Java TCP transport and PostgreSQL transactions.",
};

export default function ProductsPage() {
  return (
    <Suspense
      fallback={
        <div className="mx-auto max-w-6xl animate-pulse space-y-4 p-8">
          <div className="h-8 w-48 rounded-lg bg-slate-200" />
          <div className="h-64 rounded-xl border border-slate-200 bg-slate-100" />
        </div>
      }
    >
      <ProductsClient />
    </Suspense>
  );
}
