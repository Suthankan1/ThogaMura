import type { Metadata } from "next";
import { RequestsClient } from "./RequestsClient";

export const metadata: Metadata = {
  title: "Stock Requests",
  description: "Live warehouse request queue and authoritative reservation history.",
};

export default function RequestsPage() {
  return <RequestsClient />;
}
