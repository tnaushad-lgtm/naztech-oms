"use client";

import { useEffect, useState } from "react";
import { get } from "@/lib/api";
import { timeOf } from "@/lib/format";

type News = { id: number; category: string; symbol: string; title: string; body: string; sentiment: string; publishedAt: string };

const senti: Record<string, string> = { POSITIVE: "bg-bull/15 text-bull", NEGATIVE: "bg-bear/15 text-bear", NEUTRAL: "bg-surface/[0.1] text-ink-400" };
const catColor: Record<string, string> = { PRICE_SENSITIVE: "bg-amber-400/15 text-amber-300", HALT: "bg-bear/15 text-bear", DIVIDEND: "bg-bull/15 text-bull", AGM: "bg-aurora-cyan/15 text-aurora-cyan" };

export function NewsPanel({ symbol }: { symbol?: string }) {
  const [news, setNews] = useState<News[]>([]);
  const [onlyThis, setOnlyThis] = useState(false);

  useEffect(() => {
    const load = async () => { try { setNews(await get("/api/news")); } catch {} };
    load(); const t = setInterval(load, 8000); return () => clearInterval(t);
  }, []);

  const shown = onlyThis && symbol ? news.filter((n) => n.symbol === symbol) : news;

  return (
    <div className="glass flex h-full flex-col overflow-hidden">
      <div className="flex items-center justify-between px-4 py-3">
        <div className="panel-title">News &amp; Announcements</div>
        {symbol && (
          <button onClick={() => setOnlyThis((v) => !v)}
            className={`chip ${onlyThis ? "bg-aurora-indigo/20 text-aurora-cyan" : "bg-surface/[0.1] text-ink-500"}`}>
            {onlyThis ? `Only ${symbol}` : "All"}
          </button>
        )}
      </div>
      <div className="flex-1 space-y-2 overflow-auto px-3 pb-3">
        {shown.map((n) => (
          <div key={n.id} className="rounded-xl border border-line/[0.1] bg-surface/[0.05] p-2.5">
            <div className="flex items-center gap-1.5">
              <span className={`chip ${catColor[n.category] || "bg-surface/[0.1] text-ink-500"}`}>{n.category.replace(/_/g, " ")}</span>
              {n.symbol && <span className="text-[11px] font-semibold text-aurora-cyan">{n.symbol}</span>}
              {n.sentiment && <span className={`ml-auto chip ${senti[n.sentiment] || ""}`}>{n.sentiment}</span>}
            </div>
            <div className="mt-1 text-[12px] font-medium text-ink-200">{n.title}</div>
            {n.body && <div className="mt-0.5 line-clamp-2 text-[11px] text-ink-500">{n.body}</div>}
            <div className="mt-1 text-[10px] text-ink-600">{timeOf(n.publishedAt)}</div>
          </div>
        ))}
        {shown.length === 0 && <div className="grid h-full place-items-center text-[12px] text-ink-600">No announcements.</div>}
      </div>
    </div>
  );
}
