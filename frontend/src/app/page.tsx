import Link from "next/link";

const technicalConcepts = [
  {
    title: "Java TCP Sockets & Framing",
    member: "Member 1 (@sankharavindu)",
    concept: "java.net.Socket, newline framing, frame deadline & safe same-ID retries",
    href: "/requests/new",
    actionLabel: "Test TCP Command Path",
  },
  {
    title: "Java URI / HttpURLConnection",
    member: "Member 2 (@sandunikarunarathna)",
    concept: "Outbound supplier HTTP lookup, timeouts, SSRF allowlisting & advisory states",
    href: "/suppliers",
    actionLabel: "Query Supplier",
  },
  {
    title: "Java NIO Selectors & Channels",
    member: "Member 3 (@Suthankan1)",
    concept: "Version-only invalidation broadcast on private demo LAN & SSE browser bridge",
    href: "/activity",
    actionLabel: "View Live Signals",
  },
  {
    title: "PostgreSQL Transactions & Concurrency",
    member: "Member 4 (@Yasiru-ruwantha)",
    concept: "Atomic stock reservation, durable command receipts & single-movement dispatch",
    href: "/warehouse",
    actionLabel: "Inspect Queue",
  },
  {
    title: "JSSE TLS & Identity Protection",
    member: "Member 5 (@vibhathkalsara99)",
    concept: "Server certificate verification, SAN hostname validation & fail-closed security",
    href: "/activity",
    actionLabel: "Inspect TLS Status",
  },
];

const operationalCards = [
  {
    title: "Inventory Catalog",
    desc: "Check authoritative on-hand, reserved, and available stock quantities across all central warehouse items in real time.",
    href: "/products",
    badge: "Live Stock Levels",
    badgeColor: "bg-emerald-50 text-emerald-800 border-emerald-200",
    action: "Browse Inventory →",
    actionColor: "text-emerald-800 hover:text-emerald-950",
  },
  {
    title: "New Stock Request",
    desc: "Submit atomic stock reservations for customer fulfillment with automatic duplicate-submission protection.",
    href: "/requests/new",
    badge: "Branch Orders",
    badgeColor: "bg-teal-50 text-teal-800 border-teal-200",
    action: "Create Request →",
    actionColor: "text-teal-800 hover:text-teal-950",
  },
  {
    title: "Warehouse Fulfillment",
    desc: "Pickers and dispatchers fulfill accepted reservations through strict Pick and Dispatch lifecycle stages.",
    href: "/warehouse",
    badge: "Fulfillment Queue",
    badgeColor: "bg-blue-50 text-blue-800 border-blue-200",
    action: "Open Warehouse Queue →",
    actionColor: "text-blue-800 hover:text-blue-950",
  },
  {
    title: "External Supplier Advisory",
    desc: "Query external supplier availability and restock timelines when central warehouse inventory is depleted.",
    href: "/suppliers",
    badge: "Supplier Inquiry",
    badgeColor: "bg-amber-50 text-amber-800 border-amber-200",
    action: "Check Supplier Stock →",
    actionColor: "text-amber-800 hover:text-amber-950",
  },
];

export default function Home() {
  return (
    <div className="space-y-8">
      {/* Page Header & High-Level Action Bar */}
      <section className="rounded-2xl border border-slate-200/80 bg-white p-6 shadow-xs sm:p-8">
        <div className="flex flex-col gap-6 sm:flex-row sm:items-center sm:justify-between">
          <div className="max-w-2xl">
            <div className="flex items-center gap-2">
              <span className="inline-flex items-center rounded-full bg-teal-50 px-2.5 py-0.5 text-xs font-semibold text-teal-800 border border-teal-200">
                Central Operations
              </span>
              <span className="text-xs text-slate-400">·</span>
              <span className="text-xs font-medium text-slate-500">Branch-to-Warehouse Coordination</span>
            </div>
            <h1 className="mt-2 text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">
              Stock Coordination &amp; Fulfillment Dashboard
            </h1>
            <p className="mt-2 text-sm leading-relaxed text-slate-600">
              Check live inventory levels, reserve stock for branch orders, coordinate picking and dispatch, and query advisory supplier stock.
            </p>
          </div>

          {/* Primary Quick Actions */}
          <div className="flex flex-wrap sm:flex-nowrap items-center gap-3 shrink-0">
            <Link
              href="/requests/new"
              className="inline-flex min-h-[44px] items-center justify-center rounded-xl bg-teal-700 px-5 py-2.5 text-sm font-semibold text-white shadow-xs hover:bg-teal-800 focus:outline-hidden focus:ring-2 focus:ring-teal-600 transition-colors"
            >
              + New Request
            </Link>
            <Link
              href="/products"
              className="inline-flex min-h-[44px] items-center justify-center rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus:outline-hidden focus:ring-2 focus:ring-teal-600 transition-colors"
            >
              Browse Stock
            </Link>
            <Link
              href="/warehouse"
              className="inline-flex min-h-[44px] items-center justify-center rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 hover:bg-slate-50 focus:outline-hidden focus:ring-2 focus:ring-teal-600 transition-colors"
            >
              Fulfillment Queue
            </Link>
          </div>
        </div>
      </section>

      {/* Operational Workflows Grid */}
      <section aria-labelledby="operations-title">
        <div className="mb-4 flex items-center justify-between">
          <h2 id="operations-title" className="text-lg font-bold text-slate-900 sm:text-xl">
            Core Workflows
          </h2>
          <span className="text-xs font-medium text-slate-500">Select an operational workspace</span>
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          {operationalCards.map((op) => (
            <div
              key={op.title}
              className="flex flex-col justify-between rounded-xl border border-slate-200/80 bg-white p-5 shadow-xs transition-all hover:border-teal-200 hover:shadow-sm"
            >
              <div>
                <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-semibold ${op.badgeColor}`}>
                  {op.badge}
                </span>
                <h3 className="mt-2.5 text-base font-bold text-slate-900">{op.title}</h3>
                <p className="mt-1.5 text-xs leading-relaxed text-slate-600">{op.desc}</p>
              </div>
              <div className="mt-5 border-t border-slate-100 pt-3">
                <Link
                  href={op.href}
                  className={`inline-flex items-center text-xs font-bold ${op.actionColor}`}
                >
                  {op.action}
                </Link>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* Dedicated Academic Architecture & Verification Disclosure (For Lecturer / Evaluation) */}
      <section>
        <details className="group rounded-2xl border border-slate-200/80 bg-white shadow-xs transition-all">
          <summary className="flex cursor-pointer items-center justify-between p-5 text-sm font-bold text-slate-900 hover:bg-slate-50 rounded-2xl select-none">
            <div className="flex items-center gap-2.5">
              <span className="flex h-6 w-6 items-center justify-center rounded-md bg-teal-100 text-teal-800 text-xs font-bold">
                ⚙
              </span>
              <span>Academic Architecture &amp; Member Evaluation Reference (Assignment 02)</span>
            </div>
            <span className="text-xs font-medium text-slate-500 group-open:rotate-180 transition-transform">
              ▼
            </span>
          </summary>

          <div className="border-t border-slate-100 p-5 space-y-6">
            <p className="text-xs text-slate-600 leading-relaxed">
              ThogaMura demonstrates five distinct Java networking and distributed system concepts owned individually by team members, complying strictly with Coursework Assignment 02:
            </p>

            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {technicalConcepts.map((item) => (
                <div
                  key={item.title}
                  className="rounded-xl border border-slate-200/60 bg-slate-50/70 p-4 text-xs space-y-2"
                >
                  <div className="font-bold text-slate-900">{item.title}</div>
                  <div className="font-semibold text-teal-800">{item.member}</div>
                  <p className="text-slate-600 leading-normal">{item.concept}</p>
                  <div className="pt-1">
                    <Link
                      href={item.href}
                      className="inline-flex items-center font-bold text-teal-700 hover:text-teal-900"
                    >
                      {item.actionLabel} →
                    </Link>
                  </div>
                </div>
              ))}
            </div>

            {/* Non-Negotiable Architecture Invariants */}
            <div className="rounded-xl border border-teal-200 bg-teal-50/70 p-5 text-slate-800">
              <h3 className="text-sm font-bold text-teal-950">Architectural Invariants</h3>
              <ul className="mt-2.5 grid gap-2 text-xs text-slate-700 sm:grid-cols-2">
                <li className="flex items-start gap-2">
                  <span className="font-bold text-teal-800">1.</span>
                  <span>PostgreSQL 17 is the sole database authority; no direct browser or gateway DB access.</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="font-bold text-teal-800">2.</span>
                  <span>Stock commands travel across Java TCP/TLS sockets in a separate JVM process.</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="font-bold text-teal-800">3.</span>
                  <span>Java NIO on port 7444 delivers private-LAN version-only invalidation signals.</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="font-bold text-teal-800">4.</span>
                  <span>Supplier inquiries use outbound Java HTTP networking to an independent mock service.</span>
                </li>
              </ul>
            </div>
          </div>
        </details>
      </section>
    </div>
  );
}
