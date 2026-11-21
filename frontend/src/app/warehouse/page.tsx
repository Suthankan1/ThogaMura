import type { Metadata } from "next";
import { WarehouseQueueClient } from "./WarehouseQueueClient";

export const metadata: Metadata = {
  title: "Warehouse Fulfillment Queue",
  description: "Picker queue executing verified state transitions from ACCEPTED to PICKED to DISPATCHED.",
};

export default function WarehousePage() {
  return <WarehouseQueueClient />;
}
