import type { Metadata } from "next";
import { ScaffoldNotice } from "@/components/ScaffoldNotice";

export const metadata: Metadata = { title: "Supplier Availability" };

export default function SuppliersPage() {
  return (
    <ScaffoldNotice
      heading="Advisory supplier availability"
      purpose="When warehouse stock is unavailable, look up a configured supplier service over Java HTTP/URL networking. Supplier results are informational and cannot reserve central warehouse stock."
      plannedFeatures={[
        "Query a product SKU through the Spring Boot gateway and the independent supplier HTTP service",
        "Show supplier-reported quantity, source and check timestamp only when verified",
        "Distinguish unavailable supplier, unknown stock, timeouts and invalid responses",
        "No automatic purchases or stock adjustments",
      ]}
    />
  );
}
