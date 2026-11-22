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

const operationalActions = [
  {
    title: "Browse Stock",
    desc: "Check authoritative on-hand, reserved, and available quantities across all warehouse items in real time.",
    href: "/products",
    badge: "Inventory Catalog",
    cta: "Browse Stock →",
  },
  {
    title: "Create Request",
    desc: "Submit a new stock reservation for customer fulfillment with automatic duplicate-submission protection.",
    href: "/requests/new",
    badge: "Branch Orders",
    cta: "Create Request →",
  },
  {
    title: "Open Warehouse Queue",
    desc: "Review accepted reservations and execute pick and dispatch stages to fulfill branch orders.",
    href: "/warehouse",
    badge: "Fulfillment",
    cta: "Open Warehouse Queue →",
  },
  {
    title: "Check Supplier Stock",
    desc: "Look up advisory availability and restock turnaround from external suppliers when central inventory is depleted.",
    href: "/suppliers",
    badge: "External Advisory",
    cta: "Check Supplier Stock →",
  },
];

export default function Home() {
  return (
    <div className="space-y-10">
      {/* Hero Section with Primary Daily Actions (QA-13) */}
      <section className="rounded-3xl bg-slate-900 px-6 py-10 text-white shadow-xl sm:px-12 sm:py-14">
        <div className="flex flex-wrap items-center gap-2.5">
          <span className="rounded-full bg-teal-400/10 border border-teal-400/30 px-3 py-0.5 text-xs font-semibold tracking-wider uppercase text-teal-300">
            Branch-to-Warehouse Coordination
          </span>
          <span className="rounded-full bg-emerald-400/10 border border-emerald-400/30 px-3 py-0.5 text-xs font-semibold text-emerald-300">
            Authoritative Inventory
          </span>
        </div>
        <h1 className="mt-4 max-w-3xl text-3xl font-bold tracking-tight sm:text-5xl">
          Central Stock Management &amp; Fulfillment
        </h1>
        <p className="mt-3 max-w-2xl text-sm leading-relaxed text-slate-300 sm:text-base">
          Check live inventory levels, reserve stock for branch orders, coordinate picking and dispatch, and query advisory supplier stock.
        </p>

        {/* Primary Three Homepage Actions (QA-13) */}
        <div className="mt-8 flex flex-wrap gap-3">
          <Link
            href="/products"
            className="inline-flex items-center rounded-xl bg-teal-600 px-5 py-3 text-sm font-semibold text-white shadow-sm hover:bg-teal-500 focus:outline-hidden focus:ring-2 focus:ring-teal-400"
          >
            Browse stock
          </Link>
          <Link
            href="/requests/new"
            className="inline-flex items-center rounded-xl border border-slate-700 bg-slate-800 px-5 py-3 text-sm font-semibold text-white hover:bg-slate-700 focus:outline-hidden focus:ring-2 focus:ring-slate-500"
          >
            Create request
          </Link>
          <Link
            href="/warehouse"
            className="inline-flex items-center rounded-xl border border-slate-700 bg-slate-800 px-5 py-3 text-sm font-semibold text-white hover:bg-slate-700 focus:outline-hidden focus:ring-2 focus:ring-slate-500"
          >
            Open warehouse queue
          </Link>
        </div>
      </section>

      {/* Operational Workflows Grid */}
      <section aria-labelledby="operations-title">
        <div className="mb-5 flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="operations-title" className="text-xl font-bold text-slate-900 sm:text-2xl">
            Operational Workflows
          </h2>
          <span className="text-xs text-slate-500">
            Real-time branch inventory dashboard
          </span>
        </div>
        <div className="grid gap-5 sm:grid-cols-2">
          {operationalActions.map((op) => (
            <div
              key={op.title}
              className="flex flex-col justify-between rounded-2xl border border-slate-200 bg-white p-6 shadow-xs transition-shadow hover:shadow-md"
            >
              <div>
                <span className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-semibold text-slate-700">
                  {op.badge}
                </span>
                <h3 className="mt-3 text-lg font-bold text-slate-900">{op.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-slate-600">{op.desc}</p>
              </div>
              <div className="mt-6 border-t border-slate-100 pt-4">
                <Link
                  href={op.href}
                  className="inline-flex items-center text-sm font-semibold text-teal-700 hover:text-teal-800"
                >
                  {op.cta}
                </Link>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* Dedicated Academic Architecture & Demo Contributions Panel (QA-13) */}
      <details className="rounded-2xl border border-slate-200 bg-white p-6 shadow-xs group" open>
        <summary className="cursor-pointer select-none font-bold text-lg text-slate-900 hover:text-teal-700 flex items-center justify-between">
          <div>
            <span>Academic Architecture &amp; Technical Ownership</span>
            <p className="mt-1 text-xs font-normal text-slate-500">
              Java Network Programming Assignment 02 · Five distinct individually owned concepts
            </p>
          </div>
          <span className="text-slate-400 group-open:rotate-180 transition-transform">▼</span>
        </summary>

        <div className="mt-6 space-y-6 border-t border-slate-100 pt-5">
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {technicalConcepts.map((item) => (
              <div
                key={item.title}
                className="flex flex-col justify-between rounded-xl border border-slate-200 bg-slate-50/50 p-4"
              >
                <div>
                  <p className="text-xs font-semibold uppercase tracking-wider text-teal-700">
                    {item.member}
                  </p>
                  <h3 className="mt-1 text-sm font-bold text-slate-900">{item.title}</h3>
                  <p className="mt-1.5 text-xs leading-relaxed text-slate-600">{item.concept}</p>
                </div>
                <div className="mt-4 border-t border-slate-200/60 pt-2.5">
                  <Link
                    href={item.href}
                    className="text-xs font-semibold text-slate-700 hover:text-teal-700"
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
    </div>
  );
}
