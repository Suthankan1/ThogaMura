import type { Metadata } from "next";
import { ScaffoldNotice } from "@/components/ScaffoldNotice";

export const metadata: Metadata = { title: "Products" };

export default function ProductsPage() {
  return <ScaffoldNotice
    heading="Product availability"
    purpose="Browse products and see server-confirmed on-hand, reserved and available quantities."
    plannedFeatures={["Search and filter by SKU or name", "Responsive accessible product table", "Real-time refresh after NIO change signal", "Advisory supplier stock inquiry for unavailable items"]}
  />;
}
