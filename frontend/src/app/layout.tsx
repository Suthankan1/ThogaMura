import type { Metadata } from "next";
import "./globals.css";
import { StockEventsProvider } from "@/lib/events/StockEventsContext";
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
        <a href="#main" className="skip-link">Skip to main content</a>
        <StockEventsProvider>
          <Header />
          <main id="main" tabIndex={-1} className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
            {children}
          </main>
          <footer className="mx-auto max-w-6xl px-4 pb-12 pt-6 text-xs text-slate-500 sm:px-6 border-t border-slate-200/60 mt-12 flex flex-col sm:flex-row items-center justify-between gap-2">
            <p>ThogaMura Logistics Platform · Branch-to-Warehouse Stock Coordination</p>
          </footer>
        </StockEventsProvider>
      </body>
    </html>
  );
}
