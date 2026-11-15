import type { Metadata } from "next";
import { ScaffoldNotice } from "@/components/ScaffoldNotice";

export const metadata: Metadata = { title: "Warehouse queue" };

export default function WarehousePage() {
  return <ScaffoldNotice
    heading="Warehouse fulfilment"
    purpose="Picker reviews accepted stock requests, marks PICKED then DISPATCHED."
    plannedFeatures={["Accessible queue grouped by status", "Explicit server-confirmed state transitions", "Double-dispatch protection and visible errors"]}
  />;
}
