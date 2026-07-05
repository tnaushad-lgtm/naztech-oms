"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Shell } from "@/components/Shell";
import { get } from "@/lib/api";
import { useLive } from "@/lib/useLive";
import { nf, timeOf } from "@/lib/format";

type T = { symbol: string; price: number; quantity: number; side: string; executedAt: string };
const CAP = 250;

function norm(t: any): T {
  return {
    symbol: t.symbol || "?", price: +t.price || 0, quantity: +t.quantity || 0,
    side: (t.aggressorSide || "").toUpperCase(), executedAt: t.executedAt || new Date().toISOString(),
  };
}

export default function TapePage() {
  const [trades, setTrades] = useState<T[]>([]);
  const [q, setQ] = useState("");
  const bufRef = useRef<T[]>([]);

  useEffect(() => {
    get<any[]>("/api/market/tape").then((r) => { const l = (r || []).map(norm); bufRef.current = l; setTrades(l); }).catch(() => {});
  }, []);

  const { connected } = useLive((type, data) => {
    if (type !== "trade" || !data) return;
    const t: T = {
      symbol: data.symbol || "?", price: +data.price || 0, quantity: +data.qty || +data.quantity || 0,
      side: (data.side || data.aggressorSide || "").toUpperCase(), executedAt: new Date().toISOString(),
    };
    bufRef.current = [t, ...bufRef.current].slice(0, CAP);
    setTrades(bufRef.current);
  });

  const shown = useMemo(() => {
    const ql = q.trim().toUpperCase();
    return ql ? trades.filter((t) => t.symbol.toUpperCase().includes(ql)) : trades;
  }, [trades, q]);

  return (
    <Shell title="Trade Tape" connected={connected}>
      <div className="flex h-full flex-col gap-3">
        <div className="flex items-center gap-2">
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter symbol…" className="field max-w-xs py-1.5 text-xs" />
          <div className="ml-auto text-[11px] text-ink-500">
            {shown.length} prints · <span className="text-bull">buy</span> / <span className="text-bear">sell</span> · live time &amp; sales
          </div>
        </div>
        <div className="glass min-h-0 flex-1 overflow-auto">
          <table className="w-full text-[12px]">
            <thead className="sticky top-0 bg-obsidian-850/95 text-[10px] uppercase tracking-wider text-ink-600">
              <tr className="text-left">
                <th className="px-3 py-2">Time</th><th className="px-3 py-2">Side</th><th className="px-3 py-2">Symbol</th>
                <th className="px-3 py-2 text-right">Price</th><th className="px-3 py-2 text-right">Qty</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((t, i) => {
                const dir = t.side === "SELL" ? "SELL" : t.side === "BUY" ? "BUY" : "";
                const col = dir === "SELL" ? "text-bear" : dir === "BUY" ? "text-bull" : "text-ink-300";
                return (
                  <tr key={i} className="border-t border-line/[0.06] hover:bg-surface/[0.05]">
                    <td className="px-3 py-1 tnum text-ink-500">{timeOf(t.executedAt)}</td>
                    <td className={`px-3 py-1 font-bold ${col}`}>{dir === "SELL" ? "▼ SELL" : dir === "BUY" ? "▲ BUY" : "• TRADE"}</td>
                    <td className="px-3 py-1 font-semibold text-ink-100">{t.symbol}</td>
                    <td className={`px-3 py-1 text-right tnum ${col}`}>{nf(t.price)}</td>
                    <td className="px-3 py-1 text-right tnum text-ink-300">{t.quantity?.toLocaleString()}</td>
                  </tr>
                );
              })}
              {shown.length === 0 && <tr><td colSpan={5} className="px-3 py-8 text-center text-ink-600">Waiting for trades…</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
    </Shell>
  );
}
