"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Shell } from "@/components/Shell";
import { TickerTape } from "@/components/TickerTape";
import { AiSearch } from "@/components/AiSearch";
import { MarketWatch, MarketRow } from "@/components/MarketWatch";
import { PriceChart } from "@/components/PriceChart";
import { DepthLadder } from "@/components/DepthLadder";
import { OrderTicket } from "@/components/OrderTicket";
import { Blotter, Order } from "@/components/Blotter";
import { Portfolio, PortfolioView } from "@/components/Portfolio";
import { NewsPanel } from "@/components/NewsPanel";
import { get, post } from "@/lib/api";
import { useLive } from "@/lib/useLive";
import { getSession } from "@/lib/session";
import { getLists, createList, deleteList, toggleSymbol, Watchlist } from "@/lib/watchlists";
import { nf, pct, dirColor, compact, assetLabel } from "@/lib/format";

export default function Terminal() {
  const [exchange, setExchange] = useState("DSE");
  const [rows, setRows] = useState<MarketRow[]>([]);
  const [selected, setSelected] = useState<MarketRow | null>(null);
  const [flash, setFlash] = useState<Record<number, "up" | "down">>({});
  const [accounts, setAccounts] = useState<any[]>([]);
  const [accountId, setAccountId] = useState<number | null>(null);
  const [dealerId, setDealerId] = useState<number | null>(null);
  const [orders, setOrders] = useState<Order[]>([]);
  const [portfolio, setPortfolio] = useState<PortfolioView | null>(null);
  const [pickedPrice, setPickedPrice] = useState<number>(0);
  const [tapeBump, setTapeBump] = useState(0);
  const [lists, setLists] = useState<Watchlist[]>([]);
  const [activeList, setActiveList] = useState("ALL");
  const prev = useRef<Record<number, number>>({});
  const selRef = useRef<number | null>(null);
  const prevSel = useRef<{ id: number | null; ltp: number | null }>({ id: null, ltp: null });
  const [ltpDir, setLtpDir] = useState<"up" | "down" | "">("");

  const session = typeof window !== "undefined" ? getSession() : null;

  useEffect(() => { setLists(getLists()); }, []);
  const refreshLists = () => setLists(getLists());

  const activeWl = lists.find((l) => l.id === activeList);
  const filteredRows = rows.filter((r) => {
    if (activeList === "ALL") return true;
    if (activeList === "GAINERS") return r.changePct > 0;
    if (activeList === "LOSERS") return r.changePct < 0;
    return activeWl ? activeWl.symbols.includes(r.symbol) : true;
  });

  // ---- loaders ----
  const loadWatch = useCallback(async (ex: string) => {
    try {
      const data = await get<MarketRow[]>(`/api/market/watch?exchange=${ex}`);
      const fl: Record<number, "up" | "down"> = {};
      data.forEach((r) => {
        const p = prev.current[r.securityId];
        if (p !== undefined && r.ltp !== p) fl[r.securityId] = r.ltp > p ? "up" : "down";
        prev.current[r.securityId] = r.ltp;
      });
      setRows(data);
      if (Object.keys(fl).length) { setFlash(fl); setTimeout(() => setFlash({}), 700); }
      setSelected((cur) => {
        if (cur) { const u = data.find((d) => d.securityId === cur.securityId); return u || cur; }
        const first = data.find((d) => d.assetClass !== "INDEX") || data[0];
        if (first) selRef.current = first.securityId;
        return first || null;
      });
      return data;
    } catch { return undefined; }
  }, []);

  const loadBlotter = useCallback(async () => {
    if (!session?.brokerId) return;
    try { setOrders(await get<Order[]>(`/api/orders?brokerId=${session.brokerId}`)); } catch {}
  }, [session?.brokerId]);

  const loadPortfolio = useCallback(async (acc: number) => {
    try { setPortfolio(await get<PortfolioView>(`/api/portfolio/${acc}`)); } catch {}
  }, []);

  // ---- init ----
  useEffect(() => {
    (async () => {
      const s = getSession();
      setDealerId(null);
      if (s?.brokerId) {
        try {
          const accs = await get<any[]>(`/api/accounts?brokerId=${s.brokerId}`);
          setAccounts(accs);
          setAccountId(s.defaultAccountId || (accs[0]?.id ?? null));
        } catch {}
      }
    })();
  }, []);

  useEffect(() => { loadWatch(exchange); const t = setInterval(() => loadWatch(exchange), 3000); return () => clearInterval(t); }, [exchange, loadWatch]);
  useEffect(() => { loadBlotter(); }, [loadBlotter]);
  useEffect(() => { if (accountId) loadPortfolio(accountId); }, [accountId, loadPortfolio]);
  // Fallback poll: keep the blotter + portfolio fresh even if the live SSE stream is unavailable
  // (e.g. reached through a proxy/tunnel), so a FIXSIM fill shows up without a manual refresh.
  useEffect(() => {
    const t = setInterval(() => { loadBlotter(); if (accountId) loadPortfolio(accountId); }, 4000);
    return () => clearInterval(t);
  }, [loadBlotter, loadPortfolio, accountId]);
  useEffect(() => { selRef.current = selected?.securityId ?? null; }, [selected]);
  // Tick-colour the header price: green when it ticks up, red when down (reset on symbol change).
  useEffect(() => {
    if (!selected) return;
    const s = prevSel.current;
    if (s.id !== selected.securityId) { prevSel.current = { id: selected.securityId, ltp: selected.ltp }; setLtpDir(""); return; }
    if (s.ltp !== null && selected.ltp !== s.ltp) setLtpDir(selected.ltp > s.ltp ? "up" : "down");
    prevSel.current = { id: selected.securityId, ltp: selected.ltp };
  }, [selected?.securityId, selected?.ltp]);

  // honour a "Trade ↗" pick coming from the screener
  const pickApplied = useRef(false);
  useEffect(() => {
    if (pickApplied.current || !rows.length) return;
    try {
      const raw = localStorage.getItem("oms_pick");
      if (!raw) { pickApplied.current = true; return; }
      const p = JSON.parse(raw);
      if (p.exchange && p.exchange !== exchange) { setExchange(p.exchange); return; }
      const r = rows.find((x) => x.symbol === p.symbol);
      if (r) { setSelected(r); pickApplied.current = true; localStorage.removeItem("oms_pick"); }
    } catch { pickApplied.current = true; }
  }, [rows, exchange]);

  // ---- live ----
  const reloadTimer = useRef<any>(null);
  const { connected } = useLive((type) => {
    if (type === "trade" || type === "indices" || type === "market") setTapeBump((b) => b + 1);
    if (type === "order" || type === "trade") {
      clearTimeout(reloadTimer.current);
      reloadTimer.current = setTimeout(() => { loadBlotter(); if (accountId) loadPortfolio(accountId); }, 600);
    }
  });

  const onSelect = (r: MarketRow) => { setSelected(r); setPickedPrice(0); };

  const onPickFromSearch = async (securityId: number) => {
    let r = rows.find((x) => x.securityId === securityId);
    if (!r) { const data = await loadWatch(exchange); r = data?.find((x) => x.securityId === securityId); }
    if (r) onSelect(r);
  };

  const cancelOrder = async (id: number) => {
    try {
      await post(`/api/orders/${id}/cancel`, {});
      loadBlotter(); if (accountId) loadPortfolio(accountId);
    } catch {}
  };

  const header = (
    <div className="flex items-center gap-3">
      <AiSearch onPick={onPickFromSearch} />
      <div className="hidden sm:flex rounded-xl border border-line/[0.12] bg-surface/[0.05] p-0.5">
        {["DSE", "CSE"].map((ex) => (
          <button key={ex} onClick={() => setExchange(ex)}
            className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition-all
              ${exchange === ex ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white shadow-glow" : "text-ink-400 hover:text-ink-100"}`}>
            {ex}
          </button>
        ))}
      </div>
    </div>
  );

  return (
    <Shell title="Trader Terminal" connected={connected} headerRight={header}>
      {/* Fill the viewport: ticker on top, trading area flexes, blotter pinned at the
          bottom so it's always visible without scrolling. Each column scrolls internally
          instead of stretching the page — no more dead space above the blotter. */}
      <div className="flex flex-col gap-3 lg:h-full lg:min-h-0">
        <TickerTape bump={tapeBump} />

        <div className="grid grid-cols-12 gap-3 lg:min-h-0 lg:flex-1 lg:grid-rows-1">
          {/* watch */}
          <div className="col-span-12 flex flex-col gap-2 lg:col-span-3 lg:min-h-0">
            <div className="glass-soft flex items-center gap-1.5 p-1.5">
              <select className="field py-1.5 text-xs" value={activeList} onChange={(e) => setActiveList(e.target.value)}>
                <option value="ALL" className="bg-obsidian-850">All Securities</option>
                <option value="GAINERS" className="bg-obsidian-850">▲ Gainers</option>
                <option value="LOSERS" className="bg-obsidian-850">▼ Losers</option>
                {lists.map((l) => <option key={l.id} value={l.id} className="bg-obsidian-850">★ {l.name} ({l.symbols.length})</option>)}
              </select>
              {selected && activeWl && (
                <button title={`Toggle ${selected.symbol} in ${activeWl.name}`}
                  onClick={() => { toggleSymbol(activeWl.id, selected.symbol); refreshLists(); }}
                  className={`ghost-btn px-2 py-1.5 ${activeWl.symbols.includes(selected.symbol) ? "text-amber-300" : ""}`}>★</button>
              )}
              <button title="New watchlist" className="ghost-btn px-2 py-1.5"
                onClick={() => { const n = prompt("Watchlist name?"); if (n) { const wl = createList(n); refreshLists(); setActiveList(wl.id); } }}>+</button>
              {activeWl && (
                <button title="Delete watchlist" className="ghost-btn px-2 py-1.5 hover:text-bear"
                  onClick={() => { if (confirm(`Delete "${activeWl.name}"?`)) { deleteList(activeWl.id); refreshLists(); setActiveList("ALL"); } }}>🗑</button>
              )}
            </div>
            <div className="h-[440px] lg:h-auto lg:min-h-0 lg:flex-1">
              <MarketWatch rows={filteredRows} selectedId={selected?.securityId} onSelect={onSelect} flash={flash} />
            </div>
          </div>

          {/* center */}
          <div className="col-span-12 space-y-3 lg:col-span-6 lg:min-h-0 lg:overflow-y-auto lg:pr-1">
            {selected && (
              <div className="glass p-3">
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
                    <div className={`tnum text-2xl font-bold transition-colors ${ltpDir === "up" ? "text-bull" : ltpDir === "down" ? "text-bear" : "text-ink-100"}`}>{nf(selected.ltp)}</div>
                    <div className={`tnum text-sm font-semibold ${dirColor(selected.changePct)}`}>
                      {nf(selected.changeAbs)} ({pct(selected.changePct)})
                    </div>
                  </div>
                </div>
                <div className="mt-3 grid grid-cols-4 gap-2 text-center">
                  {[
                    ["High", nf(selected.high)], ["Low", nf(selected.low)],
                    ["Volume", compact(selected.volume)], ["Value", `৳${compact(selected.valueMn * 1e6)}`],
                  ].map(([l, v]) => (
                    <div key={l} className="glass-soft py-1.5">
                      <div className="text-[9.5px] uppercase tracking-wider text-ink-600">{l}</div>
                      <div className="tnum text-[13px] font-semibold text-ink-200">{v}</div>
                    </div>
                  ))}
                </div>
              </div>
            )}
            {selected && selected.assetClass !== "INDEX" && (
              <PriceChart securityId={selected.securityId} symbol={selected.symbol} exchange={selected.exchange} ltp={selected.ltp} height={250} />
            )}
            {selected && selected.assetClass !== "INDEX" && (
              <DepthLadder securityId={selected.securityId} onPickPrice={setPickedPrice} />
            )}
          </div>

          {/* right */}
          <div className="col-span-12 space-y-3 lg:col-span-3 lg:min-h-0 lg:overflow-y-auto lg:pr-1">
            <div className="glass-soft flex items-center gap-2 p-2">
              <span className="panel-title pl-1">Account</span>
              <select className="field py-1.5 text-xs" value={accountId ?? ""}
                onChange={(e) => setAccountId(parseInt(e.target.value))}>
                {accounts.map((a) => <option key={a.id} value={a.id} className="bg-obsidian-850">{a.name} · {a.boId}</option>)}
              </select>
            </div>
            <OrderTicket sec={selected} accountId={accountId} dealerId={dealerId}
              pickedPrice={pickedPrice} onPlaced={() => { loadBlotter(); if (accountId) loadPortfolio(accountId); }} />
            <div className="h-[240px]"><Portfolio p={portfolio} /></div>
            <div className="h-[220px]"><NewsPanel symbol={selected?.symbol} /></div>
          </div>
        </div>

        {/* blotter — pinned to the bottom of the viewport, always visible */}
        <div className="h-[300px] lg:h-[236px] lg:flex-none">
          <Blotter orders={orders} onCancel={cancelOrder}
            onChanged={() => { loadBlotter(); if (accountId) loadPortfolio(accountId); }} />
        </div>
      </div>
    </Shell>
  );
}
