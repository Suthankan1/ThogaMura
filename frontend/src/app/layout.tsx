import type { Metadata } from "next";
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
            <main id="main" tabIndex={-1} className="page-frame py-8">
              {children}
            </main>
            <footer className="page-frame py-4 text-xs text-slate-600 sm:px-6 border-t border-slate-200/60 flex items-center justify-between gap-2">
              <p>ThogaMura · Branch and warehouse coordination.</p>
            </footer>
          </StockEventsProvider>
        </BrandIntro>
      </body>
    </html>
  );
}
