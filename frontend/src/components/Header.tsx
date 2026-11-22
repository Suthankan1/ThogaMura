"use client";

import { useState, useEffect } from "react";
import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { ConnectionIndicator } from "./ConnectionIndicator";

const navLinks = [
  { href: "/", label: "Overview" },
  { href: "/products", label: "Inventory" },
  { href: "/requests", label: "Requests" },
  { href: "/warehouse", label: "Warehouse" },
  { href: "/suppliers", label: "Suppliers" },
  { href: "/activity", label: "System Status" },
];

export function Header() {
  const [menuOpen, setMenuOpen] = useState(false);
  const pathname = usePathname();

  // Close drawer on route change or Escape key press
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") {
        setMenuOpen(false);
      }
    }
    if (menuOpen) {
      document.body.style.overflow = "hidden";
      window.addEventListener("keydown", handleKeyDown);
    } else {
      document.body.style.overflow = "";
    }
    return () => {
      document.body.style.overflow = "";
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [menuOpen]);

  return (
    <header className="sticky top-0 z-40 border-b border-slate-200/80 bg-white/95 backdrop-blur-xs">
      <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-4 sm:px-6">
        {/* Brand */}
        <Link
          href="/"
          className="flex items-center gap-2.5 text-lg font-bold tracking-tight text-slate-900 group"
        >
          <Image
            src="/images/ThogaMura-logo.svg"
            alt="ThogaMura logo"
            width={28}
            height={28}
            className="h-7 w-7 rounded-md transition-transform group-hover:scale-105"
            priority
          />
          <span className="font-extrabold tracking-tight">
            Thoga<span className="text-teal-700">Mura</span>
          </span>
        </Link>

        {/* Desktop Navigation (>= 768px) */}
        <div className="hidden md:flex items-center gap-6">
          <nav aria-label="Primary navigation">
            <ul className="flex items-center gap-1 text-sm font-semibold text-slate-600">
              {navLinks.map((link) => {
                const isActive =
                  link.href === "/" ? pathname === "/" : pathname?.startsWith(link.href);
                return (
                  <li key={link.href}>
                    <Link
                      href={link.href}
                      aria-current={isActive ? "page" : undefined}
                      className={`inline-flex items-center rounded-lg px-3 py-1.5 transition-colors ${
                        isActive
                          ? "bg-teal-50 text-teal-800 font-bold"
                          : "hover:bg-slate-50 hover:text-teal-700"
                      }`}
                    >
                      {link.label}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </nav>
          <div className="h-5 w-px bg-slate-200" aria-hidden="true" />
          <ConnectionIndicator compact={false} />
        </div>

        {/* Mobile Top Bar Controls (< 768px) */}
        <div className="flex items-center gap-2 md:hidden">
          <ConnectionIndicator compact={true} />
          <button
            type="button"
            onClick={() => setMenuOpen((prev) => !prev)}
            aria-expanded={menuOpen}
            aria-controls="mobile-navigation-drawer"
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

      {/* Mobile Slide-Over Overlay Drawer */}
      {menuOpen && (
        <div
          id="mobile-navigation-drawer"
          role="dialog"
          aria-modal="true"
          aria-label="Mobile Navigation"
          className="fixed inset-0 z-50 md:hidden flex justify-end"
        >
          {/* Backdrop */}
          <div
            className="fixed inset-0 bg-slate-900/40 backdrop-blur-xs transition-opacity"
            onClick={() => setMenuOpen(false)}
            aria-hidden="true"
          />

          {/* Drawer Sheet */}
          <div className="relative z-10 w-4/5 max-w-xs bg-white h-full shadow-2xl flex flex-col justify-between p-5 animate-in slide-in-from-right duration-200">
            <div>
              <div className="flex items-center justify-between pb-4 border-b border-slate-100">
                <div className="flex items-center gap-2">
                  <Image
                    src="/images/ThogaMura-logo.svg"
                    alt="ThogaMura logo"
                    width={24}
                    height={24}
                    className="h-6 w-6 rounded-md"
                  />
                  <span className="font-extrabold text-base text-slate-900">
                    Thoga<span className="text-teal-700">Mura</span>
                  </span>
                </div>
                <button
                  type="button"
                  onClick={() => setMenuOpen(false)}
                  aria-label="Close navigation menu"
                  className="rounded-lg p-1.5 text-slate-500 hover:bg-slate-100 focus:outline-hidden focus:ring-2 focus:ring-teal-600"
                >
                  <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" strokeWidth="2" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              </div>

              <nav aria-label="Mobile primary navigation" className="mt-4">
                <ul className="flex flex-col gap-1.5">
                  {navLinks.map((link) => {
                    const isActive =
                      link.href === "/" ? pathname === "/" : pathname?.startsWith(link.href);
                    return (
                      <li key={link.href}>
                        <Link
                          href={link.href}
                          onClick={() => setMenuOpen(false)}
                          aria-current={isActive ? "page" : undefined}
                          className={`flex min-h-[44px] items-center rounded-xl px-3.5 text-sm font-semibold transition-colors ${
                            isActive
                              ? "bg-teal-700 text-white shadow-xs"
                              : "text-slate-700 hover:bg-slate-50 hover:text-teal-800"
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

            {/* Mobile Footer in Drawer */}
            <div className="border-t border-slate-100 pt-4 text-xs text-slate-500">
              <p className="font-medium text-slate-700">LAN Logistics Coordination</p>
              <p className="mt-0.5">Java Network Programming Assignment 02</p>
            </div>
          </div>
        </div>
      )}
    </header>
  );
}
