"use client";

import { useState, useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { ConnectionIndicator } from "./ConnectionIndicator";
import { useStockEvents } from "@/lib/events/useStockEvents";

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
  const [mounted, setMounted] = useState(false);
  const pathname = usePathname();

  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const drawerRef = useRef<HTMLDivElement | null>(null);
  const closeBtnRef = useRef<HTMLButtonElement | null>(null);
  const previousActiveElementRef = useRef<HTMLElement | null>(null);

  const { isLive, sseStatus, upstreamNioStatus, lastVersion } = useStockEvents();

  useEffect(() => {
    setMounted(true);
  }, []);

  // Close drawer on route change
  useEffect(() => {
    setMenuOpen(false);
  }, [pathname]);

  // Manage modal focus trapping and escape handling (F01, F02)
  useEffect(() => {
    if (!menuOpen) return;

    previousActiveElementRef.current = document.activeElement as HTMLElement;
    document.body.style.overflow = "hidden";

    // Focus close button initially
    const timer = setTimeout(() => {
      closeBtnRef.current?.focus();
    }, 50);

    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") {
        setMenuOpen(false);
        return;
      }

      if (e.key === "Tab" && drawerRef.current) {
        const focusableElements = drawerRef.current.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])'
        );
        if (focusableElements.length === 0) return;

        const firstElement = focusableElements[0];
        const lastElement = focusableElements[focusableElements.length - 1];

        if (e.shiftKey) {
          if (document.activeElement === firstElement) {
            e.preventDefault();
            lastElement.focus();
          }
        } else {
          if (document.activeElement === lastElement) {
            e.preventDefault();
            firstElement.focus();
          }
        }
      }
    }

    window.addEventListener("keydown", handleKeyDown);
    return () => {
      clearTimeout(timer);
      document.body.style.overflow = "";
      window.removeEventListener("keydown", handleKeyDown);
      // Restore focus to trigger button
      if (previousActiveElementRef.current && typeof previousActiveElementRef.current.focus === "function") {
        previousActiveElementRef.current.focus();
      }
    };
  }, [menuOpen]);

  // Drawer modal portal (F01: outside sticky/backdrop-filtered header to avoid painting over page)
  const mobileDrawer = mounted && menuOpen ? (
    createPortal(
      <div
        id="mobile-navigation-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="Navigation Menu"
        ref={drawerRef}
        className="fixed inset-0 z-50 flex justify-end"
      >
        {/* Full Backdrop */}
        <div
          className="fixed inset-0 bg-slate-900/60 backdrop-blur-xs transition-opacity duration-200"
          onClick={() => setMenuOpen(false)}
          aria-hidden="true"
        />

        {/* Viewport-height Opaque Sheet */}
        <div className="relative z-10 flex h-full w-4/5 max-w-xs flex-col justify-between bg-white p-5 shadow-2xl transition-transform duration-200">
          <div className="flex flex-col">
            <div className="flex items-center justify-between border-b border-slate-100 pb-4">
              <div className="flex items-center gap-2">
                <Image
                  src="/images/ThogaMura-logo.svg"
                  alt="ThogaMura logo"
                  width={24}
                  height={24}
                  className="h-6 w-6 rounded-md"
                />
                <span className="text-base font-extrabold text-slate-900">
                  Thoga<span className="text-teal-700">Mura</span>
                </span>
              </div>
              <button
                ref={closeBtnRef}
                type="button"
                onClick={() => setMenuOpen(false)}
                aria-label="Close navigation menu"
                className="rounded-lg p-2 text-slate-500 hover:bg-slate-100 focus:outline-hidden focus:ring-2 focus:ring-teal-600"
              >
                <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" strokeWidth="2" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>

            {/* Mobile Navigation Links */}
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

            {/* Clear Network Status in Mobile Drawer (F04) */}
            <div className="mt-6 rounded-xl border border-slate-200 bg-slate-50 p-3.5">
              <span className="text-[11px] font-bold uppercase tracking-wider text-slate-500 block">
                System Connection
              </span>
              <div className="mt-2 flex items-center gap-2">
                <span
                  className={`h-2.5 w-2.5 rounded-full shrink-0 ${
                    isLive ? "bg-emerald-500" : "bg-amber-500 animate-pulse"
                  }`}
                  aria-hidden="true"
                />
                <span className="text-xs font-semibold text-slate-800">
                  {isLive
                    ? lastVersion
                      ? `Live updates active (v${lastVersion})`
                      : "Live updates active"
                    : sseStatus === "CONNECTED" && upstreamNioStatus !== "CONNECTED"
                    ? "Warehouse feed interrupted"
                    : "Live updates disconnected"}
                </span>
              </div>
              <Link
                href="/activity"
                onClick={() => setMenuOpen(false)}
                className="mt-2 text-xs font-bold text-teal-700 hover:text-teal-900 block"
              >
                View System Diagnostics →
              </Link>
            </div>
          </div>

          {/* Drawer Footer */}
          <div className="border-t border-slate-100 pt-4 text-xs text-slate-500">
            <p className="font-semibold text-slate-700">LAN Logistics Coordination</p>
            <p className="mt-0.5 text-slate-400">Java Network Programming Assignment 02</p>
          </div>
        </div>
      </div>,
      document.body
    )
  ) : null;

  return (
    <>
      <header className="sticky top-0 z-40 border-b border-slate-200/80 bg-white/95 backdrop-blur-xs">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-4 sm:px-6">
          {/* Brand - Keep intrinsic width without colliding (F03) */}
          <Link
            href="/"
            className="flex shrink-0 items-center gap-2.5 text-lg font-bold tracking-tight text-slate-900 group"
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

          {/* Desktop Navigation (>= 1024px to prevent horizontal overflow on 768/820px, F03) */}
          <div className="hidden lg:flex items-center gap-5">
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
                        className={`inline-flex items-center rounded-lg px-3 py-1.5 transition-colors whitespace-nowrap ${
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
            <div className="h-5 w-px bg-slate-200 shrink-0" aria-hidden="true" />
            <ConnectionIndicator compact={false} />
          </div>

          {/* Tablet & Mobile Controls (< 1024px) */}
          <div className="flex items-center gap-2.5 lg:hidden">
            <ConnectionIndicator compact={true} />
            <button
              ref={triggerRef}
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
      </header>

      {/* Portal-rendered mobile drawer */}
      {mobileDrawer}
    </>
  );
}
