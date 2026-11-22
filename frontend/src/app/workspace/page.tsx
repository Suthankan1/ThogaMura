import { Suspense } from "react";
import type { Metadata } from "next";
import { WorkspaceClient } from "./WorkspaceClient";

export const metadata: Metadata = { title: "Branch Workspace", description: "Branch reservations and warehouse operations." };

export default function WorkspacePage() {
  return <Suspense fallback={<p>Loading the branch workspace…</p>}><WorkspaceClient /></Suspense>;
}
