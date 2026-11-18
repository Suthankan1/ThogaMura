import type { Metadata } from "next";
import { ActivityStatusClient } from "./ActivityStatusClient";

export const metadata: Metadata = { title: "Activity & Connection Status" };

export default function ActivityPage() {
  return <ActivityStatusClient />;
}
