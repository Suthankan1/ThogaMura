import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "ThogaMura | Project Starter", template: "%s | ThogaMura" },
  description: "LAN-first stock request and warehouse fulfilment — coursework scaffold",
};

const links = [
  { href: "/", label: "Overview" },
  { href: "/products", label: "Products" },
  { href: "/requests", label: "Requests" },
  { href: "/warehouse", label: "Warehouse" },
  { href: "/activity", label: "Activity" },
];

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>
        <a href="#main" className="skip-link">Skip to main content</a>
        <header className="border-b border-slate-200 bg-white">
          <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-5 px-5 py-5">
            <Link href="/" className="text-xl font-bold tracking-tight text-slate-900">
              Thoga<span className="text-teal-700">Mura</span>
            </Link>
            <nav aria-label="Primary navigation">
              <ul className="flex flex-wrap gap-x-5 gap-y-2 text-sm font-semibold text-slate-700">
                {links.map((link) => (
                  <li key={link.href}>
                    <Link href={link.href} className="inline-flex min-h-10 items-center rounded px-1 hover:text-teal-700">
                      {link.label}
                    </Link>
                  </li>
                ))}
              </ul>
            </nav>
          </div>
        </header>
        <main id="main" className="mx-auto max-w-6xl px-5 py-12">
          {children}
        </main>
        <footer className="mx-auto max-w-6xl px-5 pb-10 text-sm text-slate-500">
          Java Network Programming — Assignment 02 · Starter only, no live inventory yet.
        </footer>
      </body>
    </html>
  );
}
