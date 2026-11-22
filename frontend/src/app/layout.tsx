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
    <html lang="en">
      <body>
        <a href="#main" className="skip-link">Skip to main content</a>
        <StockEventsProvider>
          <Header />
          <main id="main" className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
            {children}
          </main>
          <footer className="mx-auto max-w-6xl px-4 pb-10 text-sm text-slate-500 sm:px-6">
            Java Network Programming — Assignment 02 · Real-time Java NIO &amp; SSE invalidation.
          </footer>
        </StockEventsProvider>
      </body>
    </html>
  );
}
