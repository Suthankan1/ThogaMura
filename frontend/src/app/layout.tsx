import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import "./globals.css";
import { StockEventsProvider } from "@/lib/events/StockEventsContext";
import { ConnectionIndicator } from "@/components/ConnectionIndicator";

export const metadata: Metadata = {
  title: { default: "ThogaMura | Project Starter", template: "%s | ThogaMura" },
  description: "LAN-first branch-to-warehouse requests and supplier availability — coursework scaffold",
  icons: {
    icon: "/images/ThogaMura-logo.svg",
  },
};

const links = [
  { href: "/", label: "Overview" },
  { href: "/products", label: "Products" },
  { href: "/suppliers", label: "Suppliers" },
  { href: "/requests", label: "Requests" },
  { href: "/warehouse", label: "Warehouse" },
  { href: "/activity", label: "Activity" },
];

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>
        <a href="#main" className="skip-link">Skip to main content</a>
        <StockEventsProvider>
          <header className="border-b border-slate-200 bg-white">
            <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-5 px-5 py-5">
              <Link href="/" className="flex items-center gap-2.5 text-xl font-bold tracking-tight text-slate-900 group">
                <Image
                  src="/images/ThogaMura-logo.svg"
                  alt="ThogaMura logo"
                  width={36}
                  height={36}
                  className="h-9 w-9 rounded-md transition-transform group-hover:scale-105"
                  priority
                />
                <span>
                  Thoga<span className="text-teal-700">Mura</span>
                </span>
              </Link>
              <div className="flex flex-wrap items-center gap-5">
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
                <ConnectionIndicator />
              </div>
            </div>
          </header>
          <main id="main" className="mx-auto max-w-6xl px-5 py-12">
            {children}
          </main>
          <footer className="mx-auto max-w-6xl px-5 pb-10 text-sm text-slate-500">
            Java Network Programming — Assignment 02 · Real-time Java NIO &amp; SSE invalidation.
          </footer>
        </StockEventsProvider>
      </body>
    </html>
  );
}
