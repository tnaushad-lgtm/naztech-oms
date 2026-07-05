"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { get } from "./api";
import { useLive } from "./useLive";
import { getSession } from "./session";

// ---- shared types (loose) ----
export type Row = {
  securityId: number; exchange: string; symbol: string; name: string; assetClass: string; sector: string;
  ltp: number; changeAbs: number; changePct: number; bid: number; ask: number; volume: number;
  valueMn: number; high: number; low: number; ycp: number; status: string;
};
export type OrderRow = {
  id: number; symbol: string; side: string; orderType: string; price: number; quantity: number;
  filledQty: number; status: string; riskScore: number; createdAt: string; rejectReason: string;
};

type Dash = {
  watch: Row[]; indices: Row[]; orders: OrderRow[]; portfolio: any; news: any[];
  accounts: any[]; accountId: number | null; setAccountId: (id: number) => void;
  exchange: string; setExchange: (e: string) => void; connected: boolean;
  sectorStats: { sector: string; avgChg: number; turnover: number; count: number; up: number; down: number }[];
  breadth: { up: number; down: number; flat: number };
  gainers: Row[]; losers: Row[]; active: Row[];
  assetTurnover: { label: string; value: number }[];
  orderStatus: { label: string; value: number }[];
  riskBuckets: { label: string; value: number }[];
};

const Ctx = createContext<Dash | null>(null);
export const useDash = () => useContext(Ctx)!;

export function DashboardProvider({ children }: { children: React.ReactNode }) {
  const [exchange, setExchange] = useState("DSE");
  const [watch, setWatch] = useState<Row[]>([]);
  const [indices, setIndices] = useState<Row[]>([]);
  const [orders, setOrders] = useState<OrderRow[]>([]);
  const [portfolio, setPortfolio] = useState<any>(null);
  const [news, setNews] = useState<any[]>([]);
  const [accounts, setAccounts] = useState<any[]>([]);
  const [accountId, setAccountId] = useState<number | null>(null);
  const session = typeof window !== "undefined" ? getSession() : null;

  const loadCore = useCallback(async (ex: string) => {
    try { setWatch(await get(`/api/market/watch?exchange=${ex}`)); } catch {}
    try { setIndices(await get(`/api/market/indices`)); } catch {}
    if (session?.brokerId) { try { setOrders(await get(`/api/orders?brokerId=${session.brokerId}`)); } catch {} }
    try { setNews(await get(`/api/news`)); } catch {}
  }, [session?.brokerId]);

  const loadPortfolio = useCallback(async (acc: number) => {
    try { setPortfolio(await get(`/api/portfolio/${acc}`)); } catch {}
  }, []);

  useEffect(() => {
    (async () => {
      if (session?.brokerId) {
        try {
          const accs = await get<any[]>(`/api/accounts?brokerId=${session.brokerId}`);
          setAccounts(accs);
          setAccountId(session.defaultAccountId || accs[0]?.id || null);
        } catch {}
      }
    })();
  }, []);

  useEffect(() => { loadCore(exchange); const t = setInterval(() => loadCore(exchange), 4000); return () => clearInterval(t); }, [exchange, loadCore]);
  useEffect(() => { if (accountId) loadPortfolio(accountId); }, [accountId, loadPortfolio]);

  const t = useRef<any>(null);
  const { connected } = useLive((type) => {
    if (type === "order" || type === "trade") {
      clearTimeout(t.current);
      t.current = setTimeout(() => { loadCore(exchange); if (accountId) loadPortfolio(accountId); }, 700);
    }
  });

  // ---- aggregations ----
  const sectorStats = useMemo(() => {
    const m: Record<string, { sector: string; sumChg: number; turnover: number; count: number; up: number; down: number }> = {};
    watch.filter((r) => r.assetClass !== "INDEX").forEach((r) => {
      const s = r.sector || "Other";
      m[s] = m[s] || { sector: s, sumChg: 0, turnover: 0, count: 0, up: 0, down: 0 };
      m[s].sumChg += r.changePct; m[s].turnover += r.valueMn; m[s].count++;
      if (r.changePct > 0) m[s].up++; else if (r.changePct < 0) m[s].down++;
    });
    return Object.values(m).map((x) => ({
      sector: x.sector, avgChg: x.count ? x.sumChg / x.count : 0, turnover: x.turnover,
      count: x.count, up: x.up, down: x.down,
    })).sort((a, b) => b.turnover - a.turnover);
  }, [watch]);

  const breadth = useMemo(() => {
    let up = 0, down = 0, flat = 0;
    watch.filter((r) => r.assetClass !== "INDEX").forEach((r) =>
      r.changePct > 0 ? up++ : r.changePct < 0 ? down++ : flat++);
    return { up, down, flat };
  }, [watch]);

  const eq = useMemo(() => watch.filter((r) => r.assetClass !== "INDEX"), [watch]);
  const gainers = useMemo(() => [...eq].sort((a, b) => b.changePct - a.changePct).slice(0, 8), [eq]);
  const losers = useMemo(() => [...eq].sort((a, b) => a.changePct - b.changePct).slice(0, 8), [eq]);
  const active = useMemo(() => [...eq].sort((a, b) => b.valueMn - a.valueMn).slice(0, 8), [eq]);

  const assetTurnover = useMemo(() => {
    const m: Record<string, number> = {};
    eq.forEach((r) => { m[r.assetClass] = (m[r.assetClass] || 0) + r.valueMn; });
    return Object.entries(m).map(([label, value]) => ({ label, value })).sort((a, b) => b.value - a.value);
  }, [eq]);

  const orderStatus = useMemo(() => {
    const m: Record<string, number> = {};
    orders.forEach((o) => { m[o.status] = (m[o.status] || 0) + 1; });
    return Object.entries(m).map(([label, value]) => ({ label, value }));
  }, [orders]);

  const riskBuckets = useMemo(() => {
    const b = { "0-20": 0, "20-40": 0, "40-60": 0, "60-80": 0, "80-100": 0 };
    orders.forEach((o) => {
      const s = o.riskScore || 0;
      const k = s < 20 ? "0-20" : s < 40 ? "20-40" : s < 60 ? "40-60" : s < 80 ? "60-80" : "80-100";
      (b as any)[k]++;
    });
    return Object.entries(b).map(([label, value]) => ({ label, value }));
  }, [orders]);

  const value: Dash = {
    watch, indices, orders, portfolio, news, accounts, accountId, setAccountId, exchange, setExchange,
    connected, sectorStats, breadth, gainers, losers, active, assetTurnover, orderStatus, riskBuckets,
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
