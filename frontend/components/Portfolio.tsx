"use client";

import Link from "next/link";
import { nf, compact, dirColor, money } from "@/lib/format";

type Position = {
  securityId: number; symbol: string; name: string; quantity: number; avgCost: number;
  ltp: number; marketValue: number; unrealizedPnl: number; pnlPct: number;
};
export type PortfolioView = {
  accountId: number; accountName: string; cash: number; holdingsValue: number;
  totalValue: number; unrealizedPnl: number; positions: Position[];
};

export function Portfolio({ p }: { p: PortfolioView | null }) {
  if (!p) return <div className="glass p-4 text-[12px] text-ink-600">Select an account…</div>;
  return (
    <div className="glass flex h-full flex-col overflow-hidden">
      <div className="px-4 py-3">
        <div className="flex items-center justify-between">
          <div className="panel-title">Portfolio</div>
          <Link href="/portfolio" className="text-[11px] font-medium text-aurora-cyan hover:underline">Details →</Link>
        </div>
        <div className="mt-2 grid grid-cols-3 gap-2">
          <Stat label="Total Value" value={money(p.totalValue)} />
          <Stat label="Cash" value={money(p.cash)} />
          <Stat label="Unreal. P&L" value={money(p.unrealizedPnl)} accent={p.unrealizedPnl} />
        </div>
      </div>
      <div className="flex-1 overflow-auto">
        <table className="w-full text-[12px]">
          <thead className="sticky top-0 bg-obsidian-850/95 text-[10px] uppercase tracking-wider text-ink-600">
            <tr className="text-left">
              <th className="px-4 py-2 font-medium">Symbol</th>
              <th className="px-3 py-2 font-medium text-right">Qty</th>
              <th className="px-3 py-2 font-medium text-right">Avg</th>
              <th className="px-3 py-2 font-medium text-right">LTP</th>
              <th className="px-3 py-2 font-medium text-right">P&L</th>
            </tr>
          </thead>
          <tbody>
            {p.positions.map((pos) => (
              <tr key={pos.securityId} className="border-t border-line/[0.1]">
                <td className="px-4 py-2 font-semibold text-ink-100">{pos.symbol}</td>
                <td className="px-3 py-2 text-right tnum text-ink-200">{compact(pos.quantity)}</td>
                <td className="px-3 py-2 text-right tnum text-ink-400">{nf(pos.avgCost)}</td>
                <td className="px-3 py-2 text-right tnum text-ink-200">{nf(pos.ltp)}</td>
                <td className={`px-3 py-2 text-right tnum font-semibold ${dirColor(pos.unrealizedPnl)}`}>
                  {nf(pos.unrealizedPnl, 0)}
                  <span className="ml-1 text-[10px] opacity-80">({nf(pos.pnlPct, 1)}%)</span>
                </td>
              </tr>
            ))}
            {p.positions.length === 0 && (
              <tr><td colSpan={5} className="px-4 py-6 text-center text-[12px] text-ink-600">No open positions.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Stat({ label, value, accent }: { label: string; value: string; accent?: number }) {
  return (
    <div className="glass-soft p-2">
      <div className="text-[9.5px] uppercase tracking-wider text-ink-600">{label}</div>
      <div className={`tnum text-[13px] font-semibold ${accent === undefined ? "text-ink-100" : dirColor(accent)}`}>{value}</div>
    </div>
  );
}
