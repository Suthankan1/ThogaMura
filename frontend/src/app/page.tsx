import Link from "next/link";

const concepts = [
  { title: "TCP", detail: "Reliable command channel", who: "Member 1" },
  { title: "Java HTTP / URL", detail: "Supplier availability lookup", who: "Member 2" },
  { title: "Java NIO", detail: "Live change notifications", who: "Member 3" },
  { title: "Concurrency", detail: "Safe atomic reservations", who: "Member 4" },
  { title: "TLS", detail: "Validated secure sockets", who: "Member 5" },
];

export default function Home() {
  return (
    <div className="space-y-10">
      <section className="rounded-3xl bg-slate-900 px-7 py-12 text-white sm:px-12">
        <p className="mb-3 text-sm font-semibold uppercase tracking-widest text-teal-300">
          Assignment 02 · Team project
        </p>
        <h1 className="max-w-3xl text-4xl font-bold tracking-tight sm:text-5xl">
          Stock requests, without the guesswork.
        </h1>
        <p className="mt-5 max-w-2xl text-lg leading-relaxed text-slate-200">
          ThogaMura is planned as a LAN-first branch-to-warehouse stock
          request system powered by real Java networking.
        </p>
        <div className="mt-8 inline-flex rounded-full border border-amber-300/50 bg-amber-300/10 px-4 py-2 text-sm text-amber-200">
          Project scaffold · Business operations are not implemented yet
        </div>
      </section>
      <section aria-labelledby="features-title">
        <div className="mb-5 flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="features-title" className="text-2xl font-bold">Five lecture concepts, one workflow</h2>
          <span className="text-sm text-slate-500">Each student owns a demonstrable feature</span>
        </div>
        <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {concepts.map((concept) => (
            <li key={concept.title} className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
              <p className="mb-4 text-xs font-semibold uppercase tracking-wider text-teal-700">
                {concept.who}
              </p>
              <h3 className="text-xl font-bold">{concept.title}</h3>
              <p className="mt-2 text-sm text-slate-600">{concept.detail}</p>
            </li>
          ))}
        </ul>
      </section>
      <section className="rounded-2xl border border-teal-200 bg-teal-50 p-7" aria-labelledby="next-title">
        <h2 id="next-title" className="text-xl font-bold">Where the team starts</h2>
        <p className="mt-2 max-w-3xl text-slate-700">
          Freeze the socket protocol, build a first real cross-process command,
          then implement stock reservations and the UI. Placeholder pages exist
          for the planned workflows; none claim server data.
        </p>
        <Link href="/products" className="mt-5 inline-block rounded-lg bg-teal-800 px-5 py-3 font-semibold text-white hover:bg-teal-900">
          Explore planned screens
        </Link>
      </section>
    </div>
  );
}
