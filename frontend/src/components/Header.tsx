"use client";

import { useState } from "react";
import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { ConnectionIndicator } from "./ConnectionIndicator";

const navLinks = [
  { href: "/", label: "Overview" },
  { href: "/products", label: "Products" },
  { href: "/suppliers", label: "Suppliers" },
  { href: "/requests", label: "Requests" },
  { href: "/warehouse", label: "Warehouse" },
  { href: "/activity", label: "Activity" },
];

export function Header() {
  const [menuOpen, setMenuOpen] = useState(false);
  const pathname = usePathname();

  const currentSection =
    navLinks.find((l) => (l.href === "/" ? pathname === "/" : pathname?.startsWith(l.href)))
      ?.label ?? "Overview";

  return (
    <header className="sticky top-0 z-40 border-b border-slate-200 bg-white/95 backdrop-blur-xs">
      <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3 sm:px-6">
        {/* Brand & Section Indicator */}
        <div className="flex items-center gap-3">
          <Link
            href="/"
            className="flex items-center gap-2 text-lg font-bold tracking-tight text-slate-900 group"
          >
            <Image
              src="/images/ThogaMura-logo.svg"
              alt="ThogaMura logo"
              width={30}
              height={30}
              className="h-7 w-7 rounded-md transition-transform group-hover:scale-105"
              priority
            />
            <span>
              Thoga<span className="text-teal-700">Mura</span>
            </span>
          </Link>
          <span className="hidden text-slate-300 sm:inline" aria-hidden="true">|</span>
          <span className="text-xs font-semibold text-slate-500 hidden sm:inline">
            {currentSection}
          </span>
        </div>

        {/* Desktop Navigation */}
        <div className="hidden md:flex items-center gap-6">
          <nav aria-label="Primary navigation">
            <ul className="flex items-center gap-5 text-sm font-semibold text-slate-700">
              {navLinks.map((link) => {
                const isActive =
                  link.href === "/" ? pathname === "/" : pathname?.startsWith(link.href);
                return (
                  <li key={link.href}>
                    <Link
                      href={link.href}
                      className={`inline-flex items-center rounded-md px-2 py-1 transition-colors ${
                        isActive
                          ? "bg-teal-50 text-teal-800 font-bold"
                          : "hover:text-teal-700"
                      }`}
                    >
                      {link.label}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </nav>
          <ConnectionIndicator />
        </div>

        {/* Mobile Controls (< 768px) (QA-11: Compact height ~56px) */}
        <div className="flex items-center gap-2 md:hidden">
          <ConnectionIndicator />
          <button
            type="button"
            onClick={() => setMenuOpen((prev) => !prev)}
            aria-expanded={menuOpen}
            aria-label={menuOpen ? "Close navigation menu" : "Open navigation menu"}
            className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-slate-300 bg-white text-slate-700 hover:bg-slate-50 focus:outline-hidden focus:ring-2 focus:ring-teal-600"
          >
            {menuOpen ? (
              <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" strokeWidth="2" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            ) : (
              <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" strokeWidth="2" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 6.75h16.5M3.75 12h16.5m-16.5 5.25h16.5" />
              </svg>
            )}
          </button>
        </div>
      </div>

      {/* Mobile Drawer */}
      {menuOpen && (
        <div className="border-t border-slate-200 bg-white px-4 py-3 md:hidden shadow-lg animate-in fade-in slide-in-from-top-2">
          <nav aria-label="Mobile primary navigation">
            <ul className="flex flex-col gap-1">
              {navLinks.map((link) => {
                const isActive =
                  link.href === "/" ? pathname === "/" : pathname?.startsWith(link.href);
                return (
                  <li key={link.href}>
                    <Link
                      href={link.href}
                      onClick={() => setMenuOpen(false)}
                      className={`flex min-h-11 items-center rounded-lg px-3 text-sm font-semibold transition-colors ${
                        isActive
                          ? "bg-teal-50 text-teal-800 font-bold"
                          : "text-slate-700 hover:bg-slate-50 hover:text-teal-700"
                      }`}
                    >
                      {link.label}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </nav>
        </div>
      )}
    </header>
  );
}
