"use client";

import { useEffect, useRef, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { get } from "@/lib/api";
import { assetLabel } from "@/lib/format";

type Hit = { securityId: number; symbol: string; name: string; assetClass: string; matchPct: number };

export function AiSearch({ onPick }: { onPick: (securityId: number) => void }) {
  const [q, setQ] = useState("");
  const [open, setOpen] = useState(false);
  const [hits, setHits] = useState<Hit[]>([]);
  const [loading, setLoading] = useState(false);
  const timer = useRef<any>(null);

  useEffect(() => {
    if (!q.trim()) { setHits([]); return; }
    setLoading(true);
    clearTimeout(timer.current);
    timer.current = setTimeout(async () => {
      try {
        const r = await get<Hit[]>(`/api/ai/search?q=${encodeURIComponent(q)}&min=28&limit=8`);
        setHits(r);
      } catch { setHits([]); }
      setLoading(false);
    }, 220);
  }, [q]);

  return (
    <div className="relative w-[300px] max-w-[42vw]">
      <div className="flex items-center gap-2 rounded-xl border border-line/[0.16] bg-obsidian-900/70 px-3 py-2 focus-within:border-aurora-indigo/60 focus-within:ring-2 focus-within:ring-aurora-indigo/20">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
          className="text-aurora-cyan">
          <circle cx="11" cy="11" r="7" /><path d="M21 21l-4.3-4.3" strokeLinecap="round" />
        </svg>
        <input
          value={q}
          onChange={(e) => { setQ(e.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
          onBlur={() => setTimeout(() => setOpen(false), 150)}
          placeholder="AI search — try “telecom” or “squre pharma”"
          className="w-full bg-transparent text-sm text-ink-100 placeholder-ink-500 outline-none" />
        <span className="chip bg-aurora-violet/15 text-aurora-violet">AI</span>
      </div>

      <AnimatePresence>
        {open && q.trim() && (
          <motion.div
            initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }}
            className="absolute z-30 mt-2 w-[min(420px,90vw)] glass p-1.5">
            {loading && <div className="px-3 py-2 text-xs text-ink-500">Searching the semantic index…</div>}
            {!loading && hits.length === 0 && <div className="px-3 py-2 text-xs text-ink-500">No close matches.</div>}
            {hits.map((h) => (
              <button key={h.securityId}
                onMouseDown={() => { onPick(h.securityId); setOpen(false); setQ(""); }}
                className="flex w-full items-center justify-between rounded-lg px-3 py-2 text-left hover:bg-surface/[0.1]">
                <div className="min-w-0">
                  <div className="text-sm font-semibold text-ink-100">{h.symbol}</div>
                  <div className="truncate text-[11px] text-ink-500">{h.name}</div>
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  <span className="chip bg-surface/[0.1] text-ink-400">{assetLabel(h.assetClass)}</span>
                  <span className="tnum text-[11px] font-semibold text-aurora-cyan">{h.matchPct.toFixed(0)}%</span>
                </div>
              </button>
            ))}
            <div className="px-3 py-1.5 text-[10px] text-ink-600">
              Local all-MiniLM-L6-v2 · runs in-process, no data leaves the exchange
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
