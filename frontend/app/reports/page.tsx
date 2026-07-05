"use client";

import { useEffect, useMemo, useState } from "react";
import { Shell } from "@/components/Shell";
import { get } from "@/lib/api";
import { getSession } from "@/lib/session";
import { nf, compact, timeOf, money } from "@/lib/format";

const REPORTS = [
  { k: "tradebook", label: "Trade Book" },
  { k: "orderbook", label: "Order Book" },
  { k: "positions", label: "Position Statement" },
  { k: "eod", label: "EOD Summary" },
];

function downloadCsv(name: string, headers: string[], rows: (string | number)[][]) {
  const esc = (v: any) => `"${String(v ?? "").replace(/"/g, '""')}"`;
  const csv = [headers.map(esc).join(","), ...rows.map((r) => r.map(esc).join(","))].join("\n");
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = `${name}_${new Date().toISOString().slice(0, 10)}.csv`;
  a.click();
}

export default function Reports() {
  const session = typeof window !== "undefined" ? getSession() : null;
  const [report, setReport] = useState("tradebook");
  const [accounts, setAccounts] = useState<any[]>([]);
  const [accountId, setAccountId] = useState<number | null>(null);
  const [tradebook, setTradebook] = useState<any[]>([]);
  const [orderbook, setOrderbook] = useState<any[]>([]);
  const [portfolio, setPortfolio] = useState<any>(null);

  useEffect(() => {
    (async () => {
      if (session?.brokerId) {
        try { setTradebook(await get(`/api/reports/tradebook?brokerId=${session.brokerId}`)); } catch {}
        try { setOrderbook(await get(`/api/orders?brokerId=${session.brokerId}`)); } catch {}
        try {
          const accs = await get<any[]>(`/api/accounts?brokerId=${session.brokerId}`);
          setAccounts(accs); setAccountId(session.defaultAccountId || accs[0]?.id || null);
        } catch {}
      }
    })();
  }, []);
  useEffect(() => { if (accountId) get(`/api/portfolio/${accountId}`).then(setPortfolio).catch(() => {}); }, [accountId]);

  const eod = useMemo(() => {
    const byStatus: Record<string, number> = {};
    orderbook.forEach((o) => { byStatus[o.status] = (byStatus[o.status] || 0) + 1; });
    const turnover = tradebook.reduce((a, t) => a + (t.value || 0), 0);
    const buys = tradebook.filter((t) => t.side === "BUY").length;
    const sells = tradebook.length - buys;
    return { orders: orderbook.length, trades: tradebook.length, turnover, buys, sells, byStatus };
  }, [orderbook, tradebook]);

  const exportCsv = () => {
    if (report === "tradebook") downloadCsv("tradebook", ["Time", "Symbol", "Side", "Qty", "Price", "Value", "TradeRef"],
      tradebook.map((t) => [t.time, t.symbol, t.side, t.quantity, t.price, t.value, t.tradeRef]));
    else if (report === "orderbook") downloadCsv("orderbook", ["Time", "Ref", "Symbol", "Side", "Type", "Qty", "Price", "Filled", "Status", "Risk"],
      orderbook.map((o) => [timeOf(o.createdAt), o.orderRef, o.symbol, o.side, o.orderType, o.quantity, o.price, o.filledQty, o.status, Math.round(o.riskScore || 0)]));
    else if (report === "positions" && portfolio) downloadCsv("positions", ["Symbol", "Qty", "AvgCost", "LTP", "MktValue", "UnrealPnL", "PnL%", "Weight%"],
      portfolio.positions.map((p: any) => [p.symbol, p.quantity, p.avgCost, p.ltp, p.marketValue, p.unrealizedPnl, p.pnlPct, p.weightPct]));
    else if (report === "eod") downloadCsv("eod_summary", ["Metric", "Value"],
      [["Orders", eod.orders], ["Trades", eod.trades], ["Turnover", eod.turnover.toFixed(2)], ["Buys", eod.buys], ["Sells", eod.sells],
       ...Object.entries(eod.byStatus).map(([k, v]) => [`Orders ${k}`, v as number])]);
  };

  const headerRight = (
    <div className="no-print flex items-center gap-2">
      {(report === "positions") && accounts.length > 0 && (
        <select className="field py-1.5 text-xs max-w-[180px]" value={accountId ?? ""} onChange={(e) => setAccountId(parseInt(e.target.value))}>
          {accounts.map((a) => <option key={a.id} value={a.id} className="bg-obsidian-850">{a.name}</option>)}
        </select>
      )}
      <button onClick={exportCsv} className="ghost-btn py-1.5 text-xs">⤓ CSV</button>
      <button onClick={() => window.print()} className="aurora-btn py-1.5 text-xs">⎙ Print / PDF</button>
    </div>
  );

  const Th = ({ children, r }: { children: React.ReactNode; r?: boolean }) => <th className={`px-3 py-2 ${r ? "text-right" : "text-left"}`}>{children}</th>;

  return (
    <Shell title="Reports &amp; EOD Export" headerRight={headerRight}>
      <div className="no-print mb-4 flex flex-wrap gap-2">
        {REPORTS.map((r) => (
          <button key={r.k} onClick={() => setReport(r.k)}
            className={`rounded-xl px-4 py-2 text-sm font-medium transition-all ${report === r.k ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white shadow-glow" : "ghost-btn"}`}>{r.label}</button>
        ))}
      </div>

      <div className="printable glass overflow-hidden">
        <div className="flex items-center justify-between border-b border-line/[0.1] px-4 py-3">
          <div>
            <div className="text-sm font-bold text-ink-100">{REPORTS.find((x) => x.k === report)?.label}</div>
            <div className="text-[11px] text-ink-500">{session?.brokerName} · Naztech OMS · as of {new Date().toLocaleString("en-GB")}</div>
          </div>
        </div>

        <div className="overflow-auto">
          {report === "tradebook" && (
            <table className="w-full text-[12px]">
              <thead className="bg-obsidian-850/95 text-[10px] uppercase tracking-wider text-ink-600"><tr><Th>Time</Th><Th>Symbol</Th><Th>Side</Th><Th r>Qty</Th><Th r>Price</Th><Th r>Value</Th><Th>Trade Ref</Th></tr></thead>
              <tbody>
                {tradebook.map((t, i) => (
                  <tr key={i} className="border-t border-line/[0.1]">
                    <td className="px-3 py-1.5 tnum text-ink-500">{t.time ? new Date(t.time).toLocaleString("en-GB", { hour12: false }) : ""}</td>
                    <td className="px-3 py-1.5 font-semibold text-ink-100">{t.symbol}</td>
                    <td className={`px-3 py-1.5 font-bold ${t.side === "BUY" ? "text-bull" : "text-bear"}`}>{t.side}</td>
                    <td className="px-3 py-1.5 text-right tnum text-ink-200">{compact(t.quantity)}</td>
                    <td className="px-3 py-1.5 text-right tnum text-ink-200">{nf(t.price)}</td>
                    <td className="px-3 py-1.5 text-right tnum text-ink-300">{compact(t.value)}</td>
                    <td className="px-3 py-1.5 text-[11px] text-ink-600">{t.tradeRef}</td>
                  </tr>
                ))}
                {!tradebook.length && <tr><td colSpan={7} className="px-3 py-8 text-center text-ink-600">No executions.</td></tr>}
              </tbody>
            </table>
          )}
          {report === "orderbook" && (
            <table className="w-full text-[12px]">
              <thead className="bg-obsidian-850/95 text-[10px] uppercase tracking-wider text-ink-600"><tr><Th>Time</Th><Th>Ref</Th><Th>Symbol</Th><Th>Side</Th><Th>Type</Th><Th r>Qty</Th><Th r>Price</Th><Th r>Filled</Th><Th>Status</Th></tr></thead>
              <tbody>
                {orderbook.map((o) => (
                  <tr key={o.id} className="border-t border-line/[0.1]">
                    <td className="px-3 py-1.5 tnum text-ink-500">{timeOf(o.createdAt)}</td>
                    <td className="px-3 py-1.5 text-[11px] text-ink-600">{o.orderRef}</td>
                    <td className="px-3 py-1.5 font-semibold text-ink-100">{o.symbol}</td>
                    <td className={`px-3 py-1.5 font-bold ${o.side === "BUY" ? "text-bull" : "text-bear"}`}>{o.side}</td>
                    <td className="px-3 py-1.5 text-ink-400">{o.orderType}</td>
                    <td className="px-3 py-1.5 text-right tnum text-ink-200">{compact(o.quantity)}</td>
                    <td className="px-3 py-1.5 text-right tnum text-ink-200">{o.orderType === "MARKET" ? "MKT" : nf(o.price)}</td>
                    <td className="px-3 py-1.5 text-right tnum text-ink-300">{compact(o.filledQty)}</td>
                    <td className="px-3 py-1.5 text-ink-300">{o.status}</td>
                  </tr>
                ))}
                {!orderbook.length && <tr><td colSpan={9} className="px-3 py-8 text-center text-ink-600">No orders.</td></tr>}
              </tbody>
            </table>
          )}
          {report === "positions" && (
            <table className="w-full text-[12px]">
              <thead className="bg-obsidian-850/95 text-[10px] uppercase tracking-wider text-ink-600"><tr><Th>Symbol</Th><Th r>Qty</Th><Th r>Avg Cost</Th><Th r>LTP</Th><Th r>Mkt Value</Th><Th r>Unreal P&L</Th><Th r>Weight%</Th></tr></thead>
              <tbody>
                {(portfolio?.positions || []).map((p: any) => (
                  <tr key={p.securityId} className="border-t border-line/[0.1]">
                    <td className="px-3 py-1.5 font-semibold text-ink-100">{p.symbol}</td>
                    <td className="px-3 py-1.5 text-right tnum text-ink-200">{compact(p.quantity)}</td>
                    <td className="px-3 py-1.5 text-right tnum text-ink-400">{nf(p.avgCost)}</td>
                    <td className="px-3 py-1.5 text-right tnum text-ink-200">{nf(p.ltp)}</td>
                    <td className="px-3 py-1.5 text-right tnum text-ink-200">{compact(p.marketValue)}</td>
                    <td className="px-3 py-1.5 text-right tnum text-ink-300">{nf(p.unrealizedPnl, 0)}</td>
                    <td className="px-3 py-1.5 text-right tnum text-ink-400">{nf(p.weightPct, 1)}</td>
                  </tr>
                ))}
                {!portfolio?.positions?.length && <tr><td colSpan={7} className="px-3 py-8 text-center text-ink-600">No positions.</td></tr>}
              </tbody>
            </table>
          )}
          {report === "eod" && (
            <div className="grid grid-cols-2 gap-3 p-4 md:grid-cols-3">
              {[["Total Orders", eod.orders], ["Total Trades", eod.trades], ["Turnover", money(eod.turnover)],
                ["Buy Fills", eod.buys], ["Sell Fills", eod.sells],
                ["Portfolio Value", portfolio ? money(portfolio.totalValue) : "—"],
                ["Unrealized P&L", portfolio ? money(portfolio.unrealizedPnl) : "—"],
                ["Realized P&L", portfolio ? money(portfolio.realizedPnl) : "—"],
                ["Day P&L", portfolio ? money(portfolio.dayPnl) : "—"]].map(([l, v]) => (
                <div key={l as string} className="glass-soft p-3">
                  <div className="text-[10px] uppercase tracking-wider text-ink-600">{l}</div>
                  <div className="tnum text-lg font-bold text-ink-100">{v}</div>
                </div>
              ))}
              <div className="col-span-2 md:col-span-3 glass-soft p-3">
                <div className="mb-1 text-[10px] uppercase tracking-wider text-ink-600">Orders by status</div>
                <div className="flex flex-wrap gap-2">
                  {Object.entries(eod.byStatus).map(([k, v]) => (
                    <span key={k} className="chip bg-surface/[0.1] text-ink-300">{k}: {v as number}</span>
                  ))}
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </Shell>
  );
}
