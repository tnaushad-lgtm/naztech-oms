"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { get, post } from "@/lib/api";
import { useLive } from "@/lib/useLive";
import { getSession } from "@/lib/session";
import { getLists, createList, deleteList, toggleSymbol, Watchlist } from "@/lib/watchlists";
import type { MarketRow } from "@/components/MarketWatch";
import type { Order } from "@/components/Blotter";
import type { PortfolioView } from "@/components/Portfolio";

/**
 * Shared trading-terminal state, so the dockable FlexLayout panels (and the classic
 * grid terminal) can all read the same selected security, account, orders, etc.
 */
type TerminalCtx = {
  exchange: string; setExchange: (e: string) => void;
  rows: MarketRow[]; filteredRows: MarketRow[]; flash: Record<number, "up" | "down">;
  selected: MarketRow | null; onSelect: (r: MarketRow) => void;
  accounts: any[]; accountId: number | null; setAccountId: (id: number) => void; dealerId: number | null;
  orders: Order[]; portfolio: PortfolioView | null;
  pickedPrice: number; setPickedPrice: (n: number) => void;
  lists: Watchlist[]; activeList: string; setActiveList: (id: string) => void;
  activeWl: Watchlist | undefined; refreshLists: () => void;
  createList: typeof createList; deleteList: typeof deleteList; toggleSymbol: typeof toggleSymbol;
  connected: boolean; tapeBump: number;
  onPickFromSearch: (securityId: number) => Promise<void>;
  cancelOrder: (id: number) => Promise<void>;
  refresh: () => void;
};

const Ctx = createContext<TerminalCtx | null>(null);
export const useTerminal = () => useContext(Ctx)!;

export function TerminalProvider({ children }: { children: React.ReactNode }) {
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
        return data.find((d) => d.assetClass !== "INDEX") || data[0] || null;
      });
    } catch {}
  }, []);

  const loadBlotter = useCallback(async () => {
    if (!session?.brokerId) return;
    try { setOrders(await get<Order[]>(`/api/orders?brokerId=${session.brokerId}`)); } catch {}
  }, [session?.brokerId]);

  const loadPortfolio = useCallback(async (acc: number) => {
    try { setPortfolio(await get<PortfolioView>(`/api/portfolio/${acc}`)); } catch {}
  }, []);

  useEffect(() => {
    (async () => {
      const s = getSession();
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
  // Fallback poll: refresh blotter + portfolio even if the live SSE stream is unavailable
  // (e.g. via a proxy/tunnel), so a FIXSIM fill updates without a manual refresh.
  useEffect(() => {
    const t = setInterval(() => { loadBlotter(); if (accountId) loadPortfolio(accountId); }, 4000);
    return () => clearInterval(t);
  }, [loadBlotter, loadPortfolio, accountId]);

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

  const reloadTimer = useRef<any>(null);
  const { connected } = useLive((type) => {
    if (type === "trade" || type === "indices" || type === "market") setTapeBump((b) => b + 1);
    if (type === "order" || type === "trade") {
      clearTimeout(reloadTimer.current);
      reloadTimer.current = setTimeout(() => { loadBlotter(); if (accountId) loadPortfolio(accountId); }, 600);
    }
  });

  const onSelect = (r: MarketRow) => { setSelected(r); setPickedPrice(0); };
  const refresh = () => { loadBlotter(); if (accountId) loadPortfolio(accountId); };

  const onPickFromSearch = async (securityId: number) => {
    let r = rows.find((x) => x.securityId === securityId);
    if (!r) { await loadWatch(exchange); r = rows.find((x) => x.securityId === securityId); }
    if (r) onSelect(r);
  };

  const cancelOrder = async (id: number) => {
    try { await post(`/api/orders/${id}/cancel`, {}); refresh(); } catch {}
  };

  const value: TerminalCtx = {
    exchange, setExchange, rows, filteredRows, flash, selected, onSelect,
    accounts, accountId, setAccountId, dealerId, orders, portfolio,
    pickedPrice, setPickedPrice, lists, activeList, setActiveList, activeWl, refreshLists,
    createList, deleteList, toggleSymbol, connected, tapeBump, onPickFromSearch, cancelOrder, refresh,
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
