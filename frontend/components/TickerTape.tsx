"use client";

import { useEffect, useState } from "react";
import { get } from "@/lib/api";
import { nf, pct, dirColor } from "@/lib/format";

type Row = { symbol: string; ltp: number; changePct: number; exchange: string; assetClass: string };

export function TickerTape({ bump }: { bump?: number }) {
  const [rows, setRows] = useState<Row[]>([]);

  const load = async () => {
    try {
      const idx = await get<Row[]>("/api/market/indices");
      const dse = await get<Row[]>("/api/market/watch?exchange=DSE");
      setRows([...idx, ...dse.slice(0, 18)]);
    } catch {}
  };
  useEffect(() => { load(); const t = setInterval(load, 5000); return () => clearInterval(t); }, []);
  useEffect(() => { if (bump) load(); }, [bump]);

  if (!rows.length) return <div className="h-9" />;
  const doubled = [...rows, ...rows];

  return (
    <div className="relative overflow-hidden rounded-xl border border-line/[0.1] bg-obsidian-900/40 py-2">
      <div className="flex w-max animate-marquee gap-7 px-4">
        {doubled.map((r, i) => (
          <span key={i} className="flex items-center gap-2 whitespace-nowrap text-[12px]">
            <span className="font-semibold text-ink-200">{r.symbol}</span>
            <span className="tnum text-ink-300">{nf(r.ltp, 2)}</span>
            <span className={`tnum font-medium ${dirColor(r.changePct)}`}>{pct(r.changePct)}</span>
            <span className="text-ink-600">·</span>
          </span>
        ))}
      </div>
      <div className="pointer-events-none absolute inset-y-0 left-0 w-16 bg-gradient-to-r from-obsidian-900 to-transparent" />
      <div className="pointer-events-none absolute inset-y-0 right-0 w-16 bg-gradient-to-l from-obsidian-900 to-transparent" />
    </div>
  );
}
