type Props = {
  heading: string;
  purpose: string;
  plannedFeatures: string[];
};

export function ScaffoldNotice({ heading, purpose, plannedFeatures }: Props) {
  return (
    <section className="space-y-6" aria-labelledby="page-heading">
      <div>
        <p className="mb-2 text-sm font-semibold uppercase tracking-widest text-teal-700">
          Planned workflow · Not implemented
        </p>
        <h1 id="page-heading" className="text-3xl font-bold tracking-tight sm:text-4xl">{heading}</h1>
        <p className="mt-3 max-w-2xl text-slate-700">{purpose}</p>
      </div>
      <div className="rounded-2xl border border-slate-200 bg-white p-7">
        <h2 className="mb-4 text-xl font-semibold">Planned capabilities</h2>
        <ul className="list-inside list-disc space-y-3 text-slate-700">
          {plannedFeatures.map((item) => <li key={item}>{item}</li>)}
        </ul>
        <p role="status" className="mt-6 rounded-lg bg-amber-50 p-4 text-sm text-amber-900">
          No live backend data is connected. This screen is a project structure placeholder.
        </p>
      </div>
    </section>
  );
}
