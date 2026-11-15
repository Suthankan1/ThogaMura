import type { Metadata } from "next";
import { ScaffoldNotice } from "@/components/ScaffoldNotice";

export const metadata: Metadata = { title: "Activity" };

export default function ActivityPage() {
  return <ScaffoldNotice
    heading="Activity and connection status"
    purpose="Review stock movement history and the status of the Java networking path."
    plannedFeatures={["Persistent dispatch movement list", "Server / discovery / NIO connection indicators", "Stale-data warning and explicit refresh action"]}
  />;
}
