"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Shell } from "@/components/Shell";
import { Donut, Slice } from "@/components/Donut";
import { EquityCurve } from "@/components/EquityCurve";
import { get } from "@/lib/api";
import { useLive } from "@/lib/useLive";
import { getSession } from "@/lib/session";
import { nf, compact, money, dirColor, assetLabel } from "@/lib/format";

type Position = {
  securityId: number; symbol: string; name: string; sector: string; assetClass: string;
  quantity: number; avgCost: number; ltp: number; ycp: number; investedValue: number;
  marketValue: number; unrealizedPnl: number; pnlPct: number; dayPnl: number; dayPnlPct: number; weightPct: number;
};
type PortfolioView = {
  accountId: number; accountName: string; cash: number; buyingPower: number; invested: number;
  holdingsValue: number; totalValue: number; unrealizedPnl: number; realizedPnl: number; dayPnl: number;
  positions: Position[]; bySector: Slice[]; byAsset: Slice[];
};
type Fill = { time: string; symbol: string; side: string; quantity: number; price: number; value: number; tradeRef: string };

export default function PortfolioPage() {
  const [accounts, setAccounts] = useState<any[]>([]);
  const [accountId, setAccountId] = useState<number | null>(null);
  const [p, setP] = useState<PortfolioView | null>(null);
  const [fills, setFills] = useState<Fill[]>([]);

  const load = useCallback(async (acc: number) => {
    try { setP(await get(`/api/portfolio/${acc}`)); } catch {}
    try { setFills(await get(`/api/portfolio/${acc}/fills`)); } catch {}
  }, []);

  useEffect(() => {
    (async () => {
      const s = getSession();
      if (s?.brokerId) {
        try {
          const accs = await get<any[]>(`/api/accounts?brokerId=${s.brokerId}`);
          setAccounts(accs);
          setAccountId(s.defaultAccountId || accs[0]?.id || null);
        } catch {}
      }
    })();
  }, []);

  useEffect(() => { if (accountId) load(accountId); }, [accountId, load]);

  const t = useRef<any>(null);
  const { connected } = useLive((type) => {
    if ((type === "order" || type === "trade") && accountId) {
      clearTimeout(t.current); t.current = setTimeout(() => load(accountId), 700);
    }
  });
  useEffect(() => { const i = setInterval(() => accountId && load(accountId), 5000); return () => clearInterval(i); }, [accountId, load]);

  const assetSlices: Slice[] = (p?.byAsset || []).map((s) => ({ ...s, label: assetLabel(s.label) }));

  const cards = p ? [
    { l: "Total Value", v: money(p.totalValue), accent: undefined as number | undefined },
    { l: "Holdings", v: money(p.holdingsValue), accent: undefined },
    { l: "Cash", v: money(p.cash), accent: undefined },
    { l: "Buying Power", v: money(p.buyingPower), accent: undefined },
    { l: "Day P&L", v: money(p.dayPnl), accent: p.dayPnl },
    { l: "Unrealized P&L", v: money(p.unrealizedPnl), accent: p.unrealizedPnl },
    { l: "Realized P&L", v: money(p.realizedPnl), accent: p.realizedPnl },
  ] : [];

  const header = (
    <select className="field py-1.5 text-xs max-w-[260px]" value={accountId ?? ""}
      onChange={(e) => setAccountId(parseInt(e.target.value))}>
      {accounts.map((a) => <option key={a.id} value={a.id} className="bg-obsidian-850">{a.name} · {a.boId}</option>)}
    </select>
  );

  return (
    <Shell title="Portfolio" connected={connected} headerRight={header}>
      {/* summary cards */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 xl:grid-cols-7">
        {cards.map((c) => (
          <div key={c.l} className="glass p-3.5">
            <div className="text-[9.5px] uppercase tracking-wider text-ink-600">{c.l}</div>
            <div className={`tnum mt-1 text-[17px] font-bold ${c.accent === undefined ? "text-ink-100" : dirColor(c.accent)}`}>{c.v}</div>
          </div>
        ))}
      </div>

      {/* P&L over time */}
      <div className="mt-4 glass p-4">
        <div className="panel-title mb-2">P&amp;L Over Time · Equity Curve</div>
        <div className="h-[260px]"><EquityCurve accountId={accountId} /></div>
      </div>

      {/* allocations */}
      <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Donut title="Allocation by Sector" slices={p?.bySector || []} />
        <Donut title="Allocation by Asset Class" slices={assetSlices} />
      </div>

      {/* holdings */}
      <div className="mt-4 glass overflow-hidden">
        <div className="flex items-center justify-between px-4 py-3">
          <div className="panel-title">Holdings</div>
          <div className="text-[11px] text-ink-500">{p?.positions.length || 0} positions</div>
        </div>
        <div className="overflow-auto">
          <table className="w-full text-[12px]">
            <thead className="bg-obsidian-850/95 text-[10px] uppercase tracking-wider text-ink-600">
              <tr className="text-left">
                <th className="px-4 py-2">Symbol</th>
                <th className="px-3 py-2">Sector</th>
                <th className="px-3 py-2 text-right">Qty</th>
                <th className="px-3 py-2 text-right">Avg Cost</th>
                <th className="px-3 py-2 text-right">LTP</th>
                <th className="px-3 py-2 text-right">Invested</th>
                <th className="px-3 py-2 text-right">Mkt Value</th>
                <th className="px-3 py-2 text-right">Day P&L</th>
                <th className="px-3 py-2 text-right">Unreal. P&L</th>
                <th className="px-3 py-2 text-right">Weight</th>
              </tr>
            </thead>
            <tbody>
              {(p?.positions || []).map((pos) => (
                <tr key={pos.securityId} className="border-t border-line/[0.1] hover:bg-surface/[0.05]">
                  <td className="px-4 py-2"><span className="font-semibold text-ink-100">{pos.symbol}</span>
                    <span className="ml-1.5 chip bg-surface/[0.1] text-ink-500">{assetLabel(pos.assetClass)}</span></td>
                  <td className="px-3 py-2 text-ink-400">{pos.sector || "—"}</td>
                  <td className="px-3 py-2 text-right tnum text-ink-200">{compact(pos.quantity)}</td>
                  <td className="px-3 py-2 text-right tnum text-ink-400">{nf(pos.avgCost)}</td>
                  <td className="px-3 py-2 text-right tnum text-ink-200">{nf(pos.ltp)}</td>
                  <td className="px-3 py-2 text-right tnum text-ink-400">{compact(pos.investedValue)}</td>
                  <td className="px-3 py-2 text-right tnum text-ink-200">{compact(pos.marketValue)}</td>
                  <td className={`px-3 py-2 text-right tnum font-medium ${dirColor(pos.dayPnl)}`}>
                    {nf(pos.dayPnl, 0)}<span className="ml-1 text-[10px] opacity-75">({nf(pos.dayPnlPct, 1)}%)</span>
                  </td>
                  <td className={`px-3 py-2 text-right tnum font-semibold ${dirColor(pos.unrealizedPnl)}`}>
                    {nf(pos.unrealizedPnl, 0)}<span className="ml-1 text-[10px] opacity-75">({nf(pos.pnlPct, 1)}%)</span>
                  </td>
                  <td className="px-3 py-2">
                    <div className="flex items-center justify-end gap-2">
                      <div className="h-1.5 w-14 overflow-hidden rounded-full bg-surface/[0.14]">
                        <div className="h-full rounded-full bg-gradient-to-r from-aurora-violet to-aurora-cyan" style={{ width: `${Math.min(100, pos.weightPct)}%` }} />
                      </div>
                      <span className="tnum w-10 text-right text-[11px] text-ink-300">{nf(pos.weightPct, 1)}%</span>
                    </div>
                  </td>
                </tr>
              ))}
              {(!p || p.positions.length === 0) && (
                <tr><td colSpan={10} className="px-4 py-8 text-center text-ink-600">No open positions.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* fills ledger */}
      <div className="mt-4 glass overflow-hidden">
        <div className="flex items-center justify-between px-4 py-3">
          <div className="panel-title">Execution Ledger</div>
          <div className="text-[11px] text-ink-500">{fills.length} fills</div>
        </div>
        <div className="max-h-[300px] overflow-auto">
          <table className="w-full text-[12px]">
            <thead className="sticky top-0 bg-obsidian-850/95 text-[10px] uppercase tracking-wider text-ink-600">
              <tr className="text-left">
                <th className="px-4 py-2">Time</th><th className="px-3 py-2">Symbol</th><th className="px-3 py-2">Side</th>
                <th className="px-3 py-2 text-right">Qty</th><th className="px-3 py-2 text-right">Price</th>
                <th className="px-3 py-2 text-right">Value</th><th className="px-3 py-2">Trade Ref</th>
              </tr>
            </thead>
            <tbody>
              {fills.map((f, i) => (
                <tr key={i} className="border-t border-line/[0.1]">
                  <td className="px-4 py-2 tnum text-ink-500">{f.time ? new Date(f.time).toLocaleString("en-GB", { hour12: false }) : ""}</td>
                  <td className="px-3 py-2 font-semibold text-ink-100">{f.symbol}</td>
                  <td className={`px-3 py-2 font-bold ${f.side === "BUY" ? "text-bull" : "text-bear"}`}>{f.side}</td>
                  <td className="px-3 py-2 text-right tnum text-ink-200">{compact(f.quantity)}</td>
                  <td className="px-3 py-2 text-right tnum text-ink-200">{nf(f.price)}</td>
                  <td className="px-3 py-2 text-right tnum text-ink-300">{compact(f.value)}</td>
                  <td className="px-3 py-2 text-[11px] text-ink-600">{f.tradeRef}</td>
                </tr>
              ))}
              {fills.length === 0 && <tr><td colSpan={7} className="px-4 py-8 text-center text-ink-600">No executions yet.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
    </Shell>
  );
}
