"use client";

import { useState, useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { ConnectionIndicator } from "./ConnectionIndicator";
import { useStockEvents } from "@/lib/events/useStockEvents";

const workspaceEnabled = process.env.NEXT_PUBLIC_WORKSPACE_ENABLED === "true";
const demoLinks = [
  { href: "/", label: "Overview" },
  { href: "/products", label: "Inventory" },
  { href: "/requests", label: "Requests" },
  { href: "/warehouse", label: "Warehouse" },
  { href: "/suppliers", label: "Suppliers" },
  { href: "/activity", label: "Diagnostics" },
];

const workspaceLink = { href: "/workspace", label: "Branch Workspace" };
const navLinks = workspaceEnabled
  ? [workspaceLink, { href: "/activity", label: "Diagnostics" }]
  : demoLinks;

export function Header() {
  const pathname = usePathname();
  const [openPath, setOpenPath] = useState<string | null>(null);
  const menuOpen = openPath !== null && openPath === pathname;

  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const drawerRef = useRef<HTMLDivElement | null>(null);
  const closeBtnRef = useRef<HTMLButtonElement | null>(null);
  const previousActiveElementRef = useRef<HTMLElement | null>(null);

  const { isLive, sseStatus, upstreamNioStatus } = useStockEvents();

  // Manage modal focus trapping and escape handling (F01, F02)
  useEffect(() => {
    if (!menuOpen) return;

    previousActiveElementRef.current = document.activeElement as HTMLElement;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    // Focus close button initially
    const timer = setTimeout(() => {
      closeBtnRef.current?.focus();
    }, 50);

    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") {
        setOpenPath(null);
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
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", handleKeyDown);
      // Restore focus to trigger button
      if (previousActiveElementRef.current && typeof previousActiveElementRef.current.focus === "function") {
        previousActiveElementRef.current.focus();
      }
    };
  }, [menuOpen]);

  // Drawer modal portal (F01: outside sticky/backdrop-filtered header to avoid painting over page)
  const mobileDrawer = menuOpen ? (
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
          onClick={() => setOpenPath(null)}
          aria-hidden="true"
        />

        {/* Viewport-height Opaque Sheet */}
        <div className="relative z-10 flex h-dvh w-4/5 max-w-xs flex-col overflow-hidden bg-white shadow-2xl">
          <div className="flex min-h-0 flex-1 flex-col">
            <div className="flex shrink-0 items-center justify-between border-b border-slate-200 px-5 py-2">
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
                onClick={() => setOpenPath(null)}
                aria-label="Close navigation menu"
                className="flex h-11 w-11 items-center justify-center rounded-lg text-slate-600 hover:bg-slate-100 focus:outline-hidden focus:ring-2 focus:ring-teal-600"
              >
                <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" strokeWidth="2" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>

            {/* Mobile Navigation Links */}
            <div className="min-h-0 overflow-y-auto overscroll-contain px-5 py-3">
            <nav aria-label="Mobile primary navigation">
              <ul className="flex flex-col gap-1.5">
                {navLinks.map((link) => {
                  const isActive =
                    link.href === "/" ? pathname === "/" : pathname?.startsWith(link.href);
                  return (
                    <li key={link.href}>
                      <Link
                        href={link.href}
                        onClick={() => setOpenPath(null)}
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
                    ? "Live updates active"
                    : sseStatus === "CONNECTED" && upstreamNioStatus !== "CONNECTED"
                    ? "Warehouse feed interrupted"
                    : "Live updates disconnected"}
                </span>
              </div>
              <Link
                href="/activity"
                onClick={() => setOpenPath(null)}
                className="mt-2 inline-flex min-h-11 items-center text-sm font-semibold text-teal-800 underline"
              >
                View System Diagnostics →
              </Link>
            </div>
            </div>
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
            href={workspaceEnabled ? "/workspace" : "/"}
            className="flex shrink-0 items-center gap-2 text-lg font-bold tracking-tight text-slate-900 group"
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

          {/* Show desktop links only when the full navigation fits */}
          <div className="hidden xl:flex items-center gap-5">
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
                        className={`inline-flex min-h-11 items-center rounded-lg px-3 py-1.5 transition-colors whitespace-nowrap ${
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

          {/* Compact navigation controls */}
          <div className="flex items-center gap-2.5 xl:hidden">
            <ConnectionIndicator compact={true} />
            <button
              ref={triggerRef}
              type="button"
              onClick={() => setOpenPath(menuOpen ? null : pathname)}
              aria-expanded={menuOpen}
              aria-controls="mobile-navigation-drawer"
              aria-label={menuOpen ? "Close navigation menu" : "Open navigation menu"}
              className="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-lg border border-slate-300 bg-white text-slate-700 hover:bg-slate-50 focus:outline-hidden focus:ring-2 focus:ring-teal-600"
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
