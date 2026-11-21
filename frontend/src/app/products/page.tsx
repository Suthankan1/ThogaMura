import type { Metadata } from "next";
import { ProductsClient } from "./ProductsClient";

export const metadata: Metadata = {
  title: "Product Catalog",
  description: "Authoritative product stock levels verified through real Java TCP transport and PostgreSQL transactions.",
};

export default function ProductsPage() {
  return <ProductsClient />;
}
