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

const quickActions = [
  {
    title: "Product Catalog",
    desc: "Authoritative stock balances (on-hand, reserved, available) stored in PostgreSQL with real-time SSE cache invalidation.",
    href: "/products",
    badge: "Inventory Core",
    cta: "Browse Products →",
  },
  {
    title: "New Stock Request",
    desc: "Submit an atomic reservation request with canonical UUID v4 idempotency keys and safe retry preservation.",
    href: "/requests/new",
    badge: "Branch Orders",
    cta: "Create Request →",
  },
  {
    title: "Warehouse Fulfillment",
    desc: "Fulfillment queue executing strict ACCEPTED → PICKED → DISPATCHED transitions with immutable movement audits.",
    href: "/warehouse",
    badge: "Picker Queue",
    cta: "Open Queue →",
  },
  {
    title: "Supplier Availability",
    desc: "Advisory stock lookup via outbound Java URL networking when warehouse inventory is depleted. Strictly read-only.",
    href: "/suppliers",
    badge: "External Advisory",
    cta: "Inquire Supplier →",
  },
];

export default function Home() {
  return (
    <div className="space-y-10">
      {/* Hero Section */}
      <section className="rounded-3xl bg-slate-900 px-7 py-12 text-white shadow-xl sm:px-12">
        <div className="flex flex-wrap items-center gap-3">
          <span className="rounded-full bg-teal-400/10 border border-teal-400/30 px-3.5 py-1 text-xs font-semibold tracking-wider uppercase text-teal-300">
            Java Network Programming · Assignment 02
          </span>
          <span className="rounded-full bg-emerald-400/10 border border-emerald-400/30 px-3.5 py-1 text-xs font-semibold text-emerald-300">
            ✓ Integrated Multi-Module Architecture
          </span>
        </div>
        <h1 className="mt-4 max-w-3xl text-4xl font-bold tracking-tight sm:text-5xl">
          Branch-to-Warehouse Stock Coordination
        </h1>
        <p className="mt-4 max-w-2xl text-base leading-relaxed text-slate-300 sm:text-lg">
          A resilient, multi-process distributed stock management system. Commands travel
          over real Java TCP/TLS sockets to PostgreSQL 17 transactions, while live change invalidation
          hints stream via Java NIO selectors and Server-Sent Events.
        </p>
        <div className="mt-8 flex flex-wrap gap-4">
          <Link
            href="/products"
            className="inline-flex items-center rounded-xl bg-teal-600 px-5 py-3 text-sm font-semibold text-white shadow-sm hover:bg-teal-500 focus:outline-hidden focus:ring-2 focus:ring-teal-400"
          >
            Explore Product Inventory
          </Link>
          <Link
            href="/requests/new"
            className="inline-flex items-center rounded-xl border border-slate-700 bg-slate-800/80 px-5 py-3 text-sm font-semibold text-slate-200 hover:bg-slate-800 hover:text-white focus:outline-hidden focus:ring-2 focus:ring-slate-500"
          >
            Submit Stock Request
          </Link>
          <Link
            href="/activity"
            className="inline-flex items-center rounded-xl border border-slate-700 bg-slate-800/80 px-5 py-3 text-sm font-semibold text-slate-200 hover:bg-slate-800 hover:text-white focus:outline-hidden focus:ring-2 focus:ring-slate-500"
          >
            Live Diagnostics →
          </Link>
        </div>
      </section>

      {/* Primary Workflow Actions */}
      <section aria-labelledby="quick-actions-title">
        <div className="mb-5 flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="quick-actions-title" className="text-2xl font-bold text-slate-900">
            Core Operations &amp; Workflows
          </h2>
          <span className="text-xs font-medium text-slate-500">
            Connected via Spring Boot API Gateway
          </span>
        </div>
        <div className="grid gap-5 sm:grid-cols-2">
          {quickActions.map((qa) => (
            <div
              key={qa.title}
              className="flex flex-col justify-between rounded-2xl border border-slate-200 bg-white p-6 shadow-xs transition-shadow hover:shadow-md"
            >
              <div>
                <div className="flex items-center justify-between gap-2">
                  <span className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-semibold text-slate-700">
                    {qa.badge}
                  </span>
                </div>
                <h3 className="mt-3 text-xl font-bold text-slate-900">{qa.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-slate-600">{qa.desc}</p>
              </div>
              <div className="mt-6 border-t border-slate-100 pt-4">
                <Link
                  href={qa.href}
                  className="inline-flex items-center text-sm font-semibold text-teal-700 hover:text-teal-800"
                >
                  {qa.cta}
                </Link>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* Five Lecture Concepts */}
      <section aria-labelledby="technical-concepts-title">
        <div className="mb-5 flex flex-wrap items-baseline justify-between gap-2">
          <div>
            <h2 id="technical-concepts-title" className="text-2xl font-bold text-slate-900">
              Five Individually Owned Technical Domains
            </h2>
            <p className="mt-1 text-sm text-slate-600">
              Each student owns a discrete, verifiable Java networking or concurrency concept.
            </p>
          </div>
          <Link
            href="/activity"
            className="text-xs font-semibold text-teal-700 hover:text-teal-800"
          >
            View Real-Time Pipeline Diagnostics →
          </Link>
        </div>
        <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {technicalConcepts.map((item) => (
            <li
              key={item.title}
              className="flex flex-col justify-between rounded-2xl border border-slate-200 bg-white p-6 shadow-xs"
            >
              <div>
                <p className="text-xs font-semibold uppercase tracking-wider text-teal-700">
                  {item.member}
                </p>
                <h3 className="mt-2 text-lg font-bold text-slate-900">{item.title}</h3>
                <p className="mt-2 text-xs leading-relaxed text-slate-600">{item.concept}</p>
              </div>
              <div className="mt-5 border-t border-slate-100 pt-3">
                <Link
                  href={item.href}
                  className="text-xs font-semibold text-slate-700 hover:text-teal-700"
                >
                  {item.actionLabel} →
                </Link>
              </div>
            </li>
          ))}
        </ul>
      </section>

      {/* Architectural Guarantee Notice */}
      <section className="rounded-2xl border border-teal-200 bg-teal-50 p-7 text-slate-800">
        <h2 className="text-lg font-bold text-teal-950">Non-Negotiable Architecture Invariants</h2>
        <ul className="mt-3 grid gap-2.5 text-xs text-slate-700 sm:grid-cols-2">
          <li className="flex items-start gap-2">
            <span className="font-bold text-teal-800">1.</span>
            <span>PostgreSQL 17 is the sole authoritative inventory source; no browser/gateway direct DB access.</span>
          </li>
          <li className="flex items-start gap-2">
            <span className="font-bold text-teal-800">2.</span>
            <span>Business stock commands cross real Java TCP/TLS sockets in a separate JVM process.</span>
          </li>
          <li className="flex items-start gap-2">
            <span className="font-bold text-teal-800">3.</span>
            <span>Java NIO on port 7444 is restricted to private demo LAN and carries version-only invalidation signals.</span>
          </li>
          <li className="flex items-start gap-2">
            <span className="font-bold text-teal-800">4.</span>
            <span>Supplier stock availability is advisory, read-only, and queried via independent mock HTTP service.</span>
          </li>
        </ul>
      </section>
    </div>
  );
}
