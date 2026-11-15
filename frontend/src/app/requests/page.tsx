import type { Metadata } from "next";
import Link from "next/link";
import { ScaffoldNotice } from "@/components/ScaffoldNotice";

export const metadata: Metadata = { title: "Requests" };

export default function RequestsPage() {
  return <>
    <ScaffoldNotice
      heading="Stock requests"
      purpose="Inspect accepted, rejected, picked and dispatched stock requests."
      plannedFeatures={["Request status table with filters", "Visible retry and uncertain-submission states", "Persistent request ID and timestamps"]}
    />
    <Link href="/requests/new" className="mt-6 inline-block rounded-lg bg-teal-800 px-5 py-3 font-semibold text-white hover:bg-teal-900">
      View request form plan
    </Link>
  </>;
}
