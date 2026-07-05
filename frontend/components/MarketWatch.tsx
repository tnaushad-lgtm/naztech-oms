"use client";

import { useEffect, useRef, useState } from "react";
import { nf, pct, dirColor, compact, assetLabel } from "@/lib/format";

export type MarketRow = {
  securityId: number; exchange: string; symbol: string; name: string; assetClass: string; sector: string;
  ltp: number; changeAbs: number; changePct: number; bid: number; ask: number;
  volume: number; valueMn: number; high: number; low: number; ycp: number; status: string;
};

export function MarketWatch({
  rows, selectedId, onSelect, flash,
}: {
  rows: MarketRow[]; selectedId?: number; onSelect: (r: MarketRow) => void;
  flash: Record<number, "up" | "down">;
}) {
  // Persistent last-tick direction per security: colour the LTP green when the latest price is
  // higher than the previous tick, red when lower — standard trading-terminal behaviour.
  const prevLtp = useRef<Record<number, number>>({});
  const series = useRef<Record<number, number[]>>({});   // rolling LTP buffer per security → sparkline
  const [dir, setDir] = useState<Record<number, "up" | "down">>({});
  useEffect(() => {
    setDir((cur) => {
      const next = { ...cur };
      for (const r of rows) {
        const p = prevLtp.current[r.securityId];
        if (p !== undefined && r.ltp !== p) next[r.securityId] = r.ltp > p ? "up" : "down";
        prevLtp.current[r.securityId] = r.ltp;
        const s = (series.current[r.securityId] ||= []);
        if (s[s.length - 1] !== r.ltp) { s.push(r.ltp); if (s.length > 32) s.shift(); }
      }
      return next;
    });
  }, [rows]);
  return (
    <div className="glass flex h-full flex-col overflow-hidden p-0">
      <div className="flex items-center justify-between px-4 py-3">
        <div className="panel-title">Market Watch</div>
        <div className="text-[11px] text-ink-500">{rows.length} instruments</div>
      </div>
      <div className="grid grid-cols-[1.2fr_0.7fr_0.85fr_0.7fr] px-4 pb-1 text-[10px] uppercase tracking-wider text-ink-600">
        <span>Symbol</span><span className="text-right">Trend</span><span className="text-right">LTP</span><span className="text-right">Chg%</span>
      </div>
      <div className="flex-1 overflow-auto">
        {rows.map((r) => {
          const active = r.securityId === selectedId;
          const fl = flash[r.securityId];
          return (
            <button key={r.securityId} onClick={() => onSelect(r)}
              className={`grid w-full grid-cols-[1.2fr_0.7fr_0.85fr_0.7fr] items-center px-4 py-2 text-left transition-colors
                ${active ? "bg-aurora-indigo/10 shadow-[inset_2px_0_0_0_#6366f1]" : "hover:bg-surface/[0.05]"}
                ${fl === "up" ? "animate-flashUp" : fl === "down" ? "animate-flashDown" : ""}`}>
              <div className="min-w-0">
                <div className="flex items-center gap-1.5">
                  <span className="text-[13px] font-semibold text-ink-100">{r.symbol}</span>
                  {r.assetClass !== "EQUITY_MAIN" && (
                    <span className="chip bg-surface/[0.1] text-ink-500">{assetLabel(r.assetClass)}</span>
                  )}
                </div>
                <div className="truncate text-[10px] text-ink-600">{r.sector || r.name}</div>
              </div>
              <div className="flex justify-end pr-1"><Sparkline data={series.current[r.securityId] || []} /></div>
              <div className="text-right">
                <div className={`tnum text-[13px] font-medium transition-colors ${
                  dir[r.securityId] === "up" ? "text-bull" : dir[r.securityId] === "down" ? "text-bear" : "text-ink-100"}`}>
                  {nf(r.ltp)}
                  {dir[r.securityId] === "up" && <span className="ml-0.5 text-[9px] align-middle">▲</span>}
                  {dir[r.securityId] === "down" && <span className="ml-0.5 text-[9px] align-middle">▼</span>}
                </div>
                <div className="tnum text-[10px] text-ink-600">V {compact(r.volume)}</div>
              </div>
              <div className={`text-right tnum text-[12px] font-semibold ${dirColor(r.changePct)}`}>
                {pct(r.changePct)}
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
}

/** Tiny rolling sparkline of recent live ticks; green when the window ends up, red when down. */
function Sparkline({ data }: { data: number[] }) {
  const w = 52, h = 16;
  if (!data || data.length < 2) return <svg width={w} height={h} />;
  const min = Math.min(...data), max = Math.max(...data), range = max - min || 1;
  const n = data.length;
  const pts = data.map((v, i) => `${((i / (n - 1)) * w).toFixed(1)},${(h - ((v - min) / range) * h).toFixed(1)}`).join(" ");
  const up = data[n - 1] >= data[0];
  return (
    <svg width={w} height={h} className="overflow-visible">
      <polyline points={pts} fill="none" stroke={up ? "#22c55e" : "#fb5b6b"} strokeWidth={1.3} strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}
