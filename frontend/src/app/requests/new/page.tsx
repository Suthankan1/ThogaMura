import type { Metadata } from "next";
import { ScaffoldNotice } from "@/components/ScaffoldNotice";

export const metadata: Metadata = { title: "New stock request" };

export default function NewRequestPage() {
  return <ScaffoldNotice
    heading="Create a stock request"
    purpose="Request one SKU and positive quantity; only show success after server confirmation."
    plannedFeatures={["Required SKU and quantity with accessible validation", "One UUID per logical submission, reused for safe retries", "Confirmed ACCEPTED / REJECTED feedback; no optimistic stock mutation"]}
  />;
}
