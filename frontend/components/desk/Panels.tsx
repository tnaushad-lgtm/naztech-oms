"use client";

import { useTerminal } from "@/lib/terminalStore";
import { MarketWatch } from "@/components/MarketWatch";
import { PriceChart } from "@/components/PriceChart";
import { DepthLadder } from "@/components/DepthLadder";
import { OrderTicket } from "@/components/OrderTicket";
import { Blotter } from "@/components/Blotter";
import { Portfolio } from "@/components/Portfolio";
import { NewsPanel } from "@/components/NewsPanel";
import { nf, pct, dirColor, compact, assetLabel } from "@/lib/format";

const Fill = ({ children }: { children: React.ReactNode }) => <div className="h-full min-h-0">{children}</div>;

export function WatchPanel() {
  const t = useTerminal();
  return (
    <div className="flex h-full flex-col gap-2">
      <div className="glass-soft flex items-center gap-1.5 p-1.5">
        <select className="field py-1.5 text-xs" value={t.activeList} onChange={(e) => t.setActiveList(e.target.value)}>
          <option value="ALL" className="bg-obsidian-850">All Securities</option>
          <option value="GAINERS" className="bg-obsidian-850">▲ Gainers</option>
          <option value="LOSERS" className="bg-obsidian-850">▼ Losers</option>
          {t.lists.map((l) => <option key={l.id} value={l.id} className="bg-obsidian-850">★ {l.name} ({l.symbols.length})</option>)}
        </select>
        {t.selected && t.activeWl && (
          <button title={`Toggle ${t.selected.symbol}`} onClick={() => { t.toggleSymbol(t.activeWl!.id, t.selected!.symbol); t.refreshLists(); }}
            className={`ghost-btn px-2 py-1.5 ${t.activeWl.symbols.includes(t.selected.symbol) ? "text-amber-300" : ""}`}>★</button>
        )}
        <button title="New watchlist" className="ghost-btn px-2 py-1.5"
          onClick={() => { const n = prompt("Watchlist name?"); if (n) { const wl = t.createList(n); t.refreshLists(); t.setActiveList(wl.id); } }}>+</button>
        {t.activeWl && (
          <button title="Delete watchlist" className="ghost-btn px-2 py-1.5 hover:text-bear"
            onClick={() => { if (confirm(`Delete "${t.activeWl!.name}"?`)) { t.deleteList(t.activeWl!.id); t.refreshLists(); t.setActiveList("ALL"); } }}>🗑</button>
        )}
      </div>
      <div className="min-h-0 flex-1"><MarketWatch rows={t.filteredRows} selectedId={t.selected?.securityId} onSelect={t.onSelect} flash={t.flash} /></div>
    </div>
  );
}

export function ChartPanel() {
  const { selected } = useTerminal();
  if (!selected) return <Empty>Select a security from Market Watch.</Empty>;
  return (
    <div className="h-full overflow-auto pr-1">
      <div className="glass mb-3 p-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-bold text-ink-100">{selected.symbol}</h2>
              <span className="chip bg-surface/[0.1] text-ink-400">{assetLabel(selected.assetClass)}</span>
              <span className="chip bg-aurora-cyan/10 text-aurora-cyan">{selected.exchange}</span>
            </div>
            <div className="text-[12px] text-ink-500">{selected.name}</div>
          </div>
          <div className="text-right">
            <div className="tnum text-2xl font-bold text-ink-100">{nf(selected.ltp)}</div>
            <div className={`tnum text-sm font-semibold ${dirColor(selected.changePct)}`}>{nf(selected.changeAbs)} ({pct(selected.changePct)})</div>
          </div>
        </div>
        <div className="mt-3 grid grid-cols-4 gap-2 text-center">
          {[["High", nf(selected.high)], ["Low", nf(selected.low)], ["Volume", compact(selected.volume)], ["Value", `৳${compact(selected.valueMn * 1e6)}`]].map(([l, v]) => (
            <div key={l} className="glass-soft py-2">
              <div className="text-[9.5px] uppercase tracking-wider text-ink-600">{l}</div>
              <div className="tnum text-[13px] font-semibold text-ink-200">{v}</div>
            </div>
          ))}
        </div>
      </div>
      {selected.assetClass !== "INDEX"
        ? <PriceChart securityId={selected.securityId} symbol={selected.symbol} exchange={selected.exchange} ltp={selected.ltp} />
        : <div className="glass p-6 text-center text-[12px] text-ink-500">Indices are not tradable — pick a stock to chart.</div>}
    </div>
  );
}

export function DepthPanel() {
  const { selected, setPickedPrice } = useTerminal();
  if (!selected || selected.assetClass === "INDEX") return <Empty>No order book for this instrument.</Empty>;
  return <Fill><DepthLadder securityId={selected.securityId} onPickPrice={setPickedPrice} /></Fill>;
}

export function TicketPanel() {
  const t = useTerminal();
  return (
    <div className="flex h-full flex-col gap-2 overflow-auto pr-1">
      <div className="glass-soft flex items-center gap-2 p-2">
        <span className="panel-title pl-1">Account</span>
        <select className="field py-1.5 text-xs" value={t.accountId ?? ""} onChange={(e) => t.setAccountId(parseInt(e.target.value))}>
          {t.accounts.map((a) => <option key={a.id} value={a.id} className="bg-obsidian-850">{a.name} · {a.boId}</option>)}
        </select>
      </div>
      <OrderTicket sec={t.selected} accountId={t.accountId} dealerId={t.dealerId} pickedPrice={t.pickedPrice} onPlaced={t.refresh} />
    </div>
  );
}

export function PortfolioPanel() {
  const { portfolio } = useTerminal();
  return <Fill><Portfolio p={portfolio} /></Fill>;
}

export function NewsDeskPanel() {
  const { selected } = useTerminal();
  return <Fill><NewsPanel symbol={selected?.symbol} /></Fill>;
}

export function BlotterPanel() {
  const { orders, cancelOrder, refresh } = useTerminal();
  return <Fill><Blotter orders={orders} onCancel={cancelOrder} onChanged={refresh} /></Fill>;
}

function Empty({ children }: { children: React.ReactNode }) {
  return <div className="grid h-full place-items-center text-[12px] text-ink-600">{children}</div>;
}
