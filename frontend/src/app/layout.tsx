import type { Metadata } from "next";
import "@fontsource/inter/400.css";
import "@fontsource/inter/500.css";
import "@fontsource/inter/600.css";
import "@fontsource/inter/700.css";
import Link from "next/link";
import "./globals.css";
import { StockEventsProvider } from "@/lib/events/StockEventsContext";
import { BrandIntro } from "@/components/BrandIntro";
import { Header } from "@/components/Header";

export const metadata: Metadata = {
  title: { default: "ThogaMura | Branch-to-Warehouse Stock Coordination", template: "%s | ThogaMura" },
  description: "LAN-first branch-to-warehouse stock requests and supplier availability powered by real Java networking",
  icons: {
    icon: "/images/ThogaMura-logo.svg",
  },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" data-scroll-behavior="smooth">
      <body>
        <BrandIntro>
          <a href="#main" className="skip-link">Skip to main content</a>
          <StockEventsProvider>
            <Header />
            <main id="main" tabIndex={-1} className="page-frame py-8 sm:py-10">
              {children}
            </main>
            <footer className="site-footer">
              <div className="page-frame grid gap-5 px-0 py-6 sm:grid-cols-[1fr_auto] sm:items-end">
                <div>
                  <p className="text-sm font-bold text-slate-900">ThogaMura</p>
                  <p className="mt-1 text-xs text-slate-600">Branch and warehouse coordination.</p>
                </div>
                <nav aria-label="Footer navigation" className="flex flex-wrap gap-x-5 gap-y-2 text-xs font-semibold text-slate-600">
                  <Link className="transition-colors hover:text-teal-800" href="/products">Inventory</Link>
                  <Link className="transition-colors hover:text-teal-800" href="/requests">Requests</Link>
                  <Link className="transition-colors hover:text-teal-800" href="/warehouse">Fulfillment</Link>
                  <Link className="transition-colors hover:text-teal-800" href="/activity">Activity</Link>
                </nav>
              </div>
              <div className="border-t border-slate-200/70">
                <div className="page-frame flex min-h-10 items-center px-0 text-[11px] text-slate-500">
                  <p>&copy; 2026 ThogaMura. Warehouse operations dashboard.</p>
                </div>
              </div>
            </footer>
          </StockEventsProvider>
        </BrandIntro>
      </body>
    </html>
  );
}
