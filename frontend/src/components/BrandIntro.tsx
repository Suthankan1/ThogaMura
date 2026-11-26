"use client";

import { createContext, useContext, useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import "./brand-intro.css";

const StartIntro = createContext(() => {});
export const useBrandIntro = () => useContext(StartIntro);

export function BrandIntro({ children }: { children: ReactNode }) {
  // Zero is the server-rendered entrance. Each subsequent logo activation gets a new timeline.
  const [sequence, setSequence] = useState<number | null>(0);
  const invoker = useRef<HTMLElement | null>(null);
  const serial = useRef(0);
  const overlay = useRef<HTMLDivElement>(null);
  const shell = useRef<HTMLDivElement>(null);
  const start = useCallback(() => {
    if (overlay.current && getComputedStyle(overlay.current).visibility === "visible") return;
    invoker.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setSequence(++serial.current);
  }, []);

  useLayoutEffect(() => {
    if (sequence === null && serial.current > 0) {
      if (shell.current) shell.current.inert = false;
      (invoker.current?.isConnected ? invoker.current : document.getElementById("main"))?.focus({ preventScroll: true });
    }
  }, [sequence]);

  useEffect(() => {
    const layer = overlay.current;
    const content = shell.current;
    if (!layer || !content || sequence === null) return;
    // CSS starts at first paint. Hydration joins the running animation.
    const animation = layer.getAnimations().find(item => item instanceof CSSAnimation && item.animationName === "intro-screen");
    const duration = Number(animation?.effect?.getComputedTiming().endTime ?? 0);
    const remaining = Math.max(0, duration - Number(animation?.currentTime ?? 0));
    const deadline = performance.now() + remaining;
    content.inert = remaining > 0;
    const finish = () => {
      content.inert = false;
      setSequence(null);
    };
    const onKey = (event: KeyboardEvent) => { if (event.key === "Escape") finish(); };
    document.addEventListener("keydown", onKey);
    const catchUp = () => { if (performance.now() >= deadline) finish(); };
    const timer = window.setTimeout(finish, remaining);
    document.addEventListener("visibilitychange", catchUp);
    window.addEventListener("pageshow", catchUp);
    return () => {
      clearTimeout(timer);
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("visibilitychange", catchUp);
      window.removeEventListener("pageshow", catchUp);
      content.inert = false;
    };
  }, [sequence]);

  return <StartIntro.Provider value={start}>
    {sequence !== null && <div key={sequence} ref={overlay} className="brand-intro"
      onClick={(event) => {
        if (!(event.target instanceof HTMLElement) || !event.target.closest("button")) {
          setSequence(null);
        }
      }}>
      <span role="status" className="sr-only">Opening ThogaMura</span>
      <button type="button" className="intro-skip" onClick={() => setSequence(null)}>Skip intro</button>
      <svg aria-hidden="true" className="intro-reflection" viewBox="0 0 1200 600" preserveAspectRatio="none">
        <defs><linearGradient id="reflection" x2=".24" y2="1" gradientUnits="objectBoundingBox">
          <stop stopColor="#76eddf" stopOpacity=".02" /><stop offset=".7" stopColor="#79ecdf" stopOpacity=".12" /><stop offset="1" stopColor="#9effef" stopOpacity=".48" />
        </linearGradient></defs>
        <path d="M-80 545 Q240 355 610 456 T1300 318 Q1050 592 602 479 T-80 585Z" fill="url(#reflection)" />
      </svg>
      <div className="brand-intro-lockup" aria-hidden="true">
        <svg className="brand-intro-fox" viewBox="0 0 400 400" fill="none">
          <defs><clipPath id="intro-reveal"><circle className="brand-intro-mask" cx="326" cy="315" r="430" /></clipPath></defs>
          <image href="/images/ThogaMura-fox.svg" width="400" height="400" clipPath="url(#intro-reveal)" />
          <path className="brand-intro-cable" d="M326 315 L277 317 C235 315 212 342 163 339 S85 306 101 269" pathLength="1" stroke="#a5fff2" strokeWidth="4" strokeLinecap="round" />
          <circle className="brand-intro-spark" cx="326" cy="315" r="4" fill="#a5fff2" />
          <g transform="translate(137.5 51.4)" stroke="#16bfb4">
            <circle className="brand-intro-ring" r="18" />
          </g>
        </svg>
        <div className="brand-intro-word">Thoga<span>Mura</span></div>
      </div>
    </div>}
    <div ref={shell} className="brand-intro-shell">{children}</div>
  </StartIntro.Provider>;
}
