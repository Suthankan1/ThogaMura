import Link from "next/link";
import { primaryAction, secondaryAction, WorkspaceHeading } from "@/components/Workspace";
import { WORKSPACE_ENABLED } from "@/lib/api/workspace";

export default function NotFound() {
  return <section className="space-y-5">
    <WorkspaceHeading title="Page not found" description="This address does not match a ThogaMura page. Return to your workspace or check the connection." />
    <div className="flex flex-wrap gap-3">
      <Link href={WORKSPACE_ENABLED ? "/workspace" : "/"} className={primaryAction}>{WORKSPACE_ENABLED ? "Open workspace" : "Open overview"}</Link>
      <Link href="/activity" className={secondaryAction}>Network diagnostics</Link>
    </div>
  </section>;
}
