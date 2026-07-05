"use client";

import { useEffect, useRef, useState } from "react";

type Theme = { id: string; name: string; hint: string; dots: [string, string, string]; bg: string };

export const THEMES: Theme[] = [
  { id: "midnight", name: "Midnight", hint: "Dark · default", dots: ["#8b5cf6", "#6366f1", "#22d3ee"], bg: "#0a0c16" },
  { id: "daylight", name: "Daylight", hint: "Light / white", dots: ["#7c3aed", "#4f46e5", "#0891b2"], bg: "#f4f6fb" },
  { id: "terminal", name: "Terminal", hint: "Old-school CRT", dots: ["#f59e0b", "#84cc16", "#22c55e"], bg: "#000000" },
  { id: "ocean", name: "Ocean", hint: "Modern cool", dots: ["#0ea5e9", "#2dd4bf", "#38bdf8"], bg: "#08132a" },
  { id: "sunset", name: "Sunset", hint: "Modern warm", dots: ["#f472b6", "#fb7185", "#fb923c"], bg: "#1a0c16" },
];

export function applyTheme(id: string) {
  document.documentElement.setAttribute("data-theme", id);
  try { localStorage.setItem("oms_theme", id); } catch {}
}

export function ThemeSwitcher() {
  const [open, setOpen] = useState(false);
  const [current, setCurrent] = useState("midnight");
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setCurrent(document.documentElement.getAttribute("data-theme") || "midnight");
    const onDoc = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, []);

  const pick = (id: string) => { applyTheme(id); setCurrent(id); setOpen(false); };
  const cur = THEMES.find((t) => t.id === current) || THEMES[0];

  return (
    <div className="relative" ref={ref}>
      <button onClick={() => setOpen((o) => !o)} title="Change theme"
        className="flex items-center gap-2 rounded-full border border-line/[0.12] bg-surface/[0.04] px-2.5 py-1.5 hover:bg-surface/[0.08] transition-colors">
        <span className="flex -space-x-1">
          {cur.dots.map((c, i) => <span key={i} className="h-2.5 w-2.5 rounded-full ring-1 ring-black/20" style={{ background: c }} />)}
        </span>
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="text-ink-400"><path d="M6 9l6 6 6-6" strokeLinecap="round" strokeLinejoin="round" /></svg>
      </button>

      {open && (
        <div className="absolute right-0 z-50 mt-2 w-56 glass p-1.5">
          <div className="px-2 py-1 text-[10px] uppercase tracking-wider text-ink-500">Theme</div>
          {THEMES.map((t) => (
            <button key={t.id} onClick={() => pick(t.id)}
              className={`flex w-full items-center gap-3 rounded-lg px-2 py-2 text-left transition-colors ${current === t.id ? "bg-surface/[0.1]" : "hover:bg-surface/[0.06]"}`}>
              <span className="grid h-7 w-7 shrink-0 place-items-center rounded-md ring-1 ring-line/[0.15]" style={{ background: t.bg }}>
                <span className="flex -space-x-1">
                  {t.dots.map((c, i) => <span key={i} className="h-2 w-2 rounded-full" style={{ background: c }} />)}
                </span>
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-[13px] font-medium text-ink-100">{t.name}</span>
                <span className="block text-[10px] text-ink-500">{t.hint}</span>
              </span>
              {current === t.id && <span className="text-aurora-cyan">✓</span>}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
