"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { Shell } from "@/components/Shell";
import { get } from "@/lib/api";
import { useLive } from "@/lib/useLive";
import { getLists, toggleSymbol, createList, Watchlist } from "@/lib/watchlists";
import { nf, pct, compact, dirColor, assetLabel } from "@/lib/format";

type Row = {
  securityId: number; exchange: string; symbol: string; name: string; assetClass: string; sector: string;
  ltp: number; changePct: number; volume: number; valueMn: number; high: number; low: number;
};

export default function Screener() {
  const router = useRouter();
  const [exchange, setExchange] = useState("DSE");
  const [rows, setRows] = useState<Row[]>([]);
  const [q, setQ] = useState("");
  const [asset, setAsset] = useState("ALL");
  const [sector, setSector] = useState("ALL");
  const [dir, setDir] = useState("ALL"); // ALL/GAINERS/LOSERS
  const [minPrice, setMinPrice] = useState<string>("");
  const [maxPrice, setMaxPrice] = useState<string>("");
  const [sortKey, setSortKey] = useState<keyof Row>("valueMn");
  const [asc, setAsc] = useState(false);
  const [lists, setLists] = useState<Watchlist[]>([]);
  const [targetList, setTargetList] = useState("");

  const load = async (ex: string) => { try { setRows(await get(`/api/market/watch?exchange=${ex}`)); } catch {} };
  useEffect(() => { load(exchange); const t = setInterval(() => load(exchange), 5000); return () => clearInterval(t); }, [exchange]);
  useEffect(() => { const ls = getLists(); setLists(ls); if (ls[0]) setTargetList(ls[0].id); }, []);
  const { connected } = useLive(() => {});

  const sectors = useMemo(() => Array.from(new Set(rows.map((r) => r.sector).filter(Boolean))).sort(), [rows]);
  const assets = useMemo(() => Array.from(new Set(rows.map((r) => r.assetClass))).sort(), [rows]);

  const filtered = useMemo(() => {
    let out = rows.filter((r) => r.assetClass !== "INDEX");
    if (q) out = out.filter((r) => (r.symbol + " " + r.name).toLowerCase().includes(q.toLowerCase()));
    if (asset !== "ALL") out = out.filter((r) => r.assetClass === asset);
    if (sector !== "ALL") out = out.filter((r) => r.sector === sector);
    if (dir === "GAINERS") out = out.filter((r) => r.changePct > 0);
    if (dir === "LOSERS") out = out.filter((r) => r.changePct < 0);
    if (minPrice) out = out.filter((r) => r.ltp >= parseFloat(minPrice));
    if (maxPrice) out = out.filter((r) => r.ltp <= parseFloat(maxPrice));
    out = [...out].sort((a, b) => {
      const x = a[sortKey] as any, y = b[sortKey] as any;
      const c = typeof x === "string" ? String(x).localeCompare(String(y)) : x - y;
      return asc ? c : -c;
    });
    return out;
  }, [rows, q, asset, sector, dir, minPrice, maxPrice, sortKey, asc]);

  const sortBy = (k: keyof Row) => { if (sortKey === k) setAsc(!asc); else { setSortKey(k); setAsc(false); } };
  const arrow = (k: keyof Row) => sortKey === k ? (asc ? " ▲" : " ▼") : "";

  const trade = (r: Row) => { localStorage.setItem("oms_pick", JSON.stringify({ symbol: r.symbol, exchange: r.exchange })); router.push("/terminal"); };
  const star = (r: Row) => {
    let id = targetList;
    if (!id) { const wl = createList("My Watchlist"); setLists(getLists()); setTargetList(wl.id); id = wl.id; }
    toggleSymbol(id, r.symbol); setLists(getLists());
  };
  const inList = (sym: string) => lists.find((l) => l.id === targetList)?.symbols.includes(sym);

  const header = (
    <div className="hidden sm:flex rounded-xl border border-line/[0.12] bg-surface/[0.05] p-0.5">
      {["DSE", "CSE"].map((ex) => (
        <button key={ex} onClick={() => setExchange(ex)}
          className={`rounded-lg px-3 py-1.5 text-xs font-semibold ${exchange === ex ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white" : "text-ink-400 hover:text-ink-100"}`}>{ex}</button>
      ))}
    </div>
  );

  return (
    <Shell title="Market Screener" connected={connected} headerRight={header}>
      {/* filters */}
      <div className="glass mb-4 p-4">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4 lg:grid-cols-7">
          <input className="field" placeholder="Search symbol/name…" value={q} onChange={(e) => setQ(e.target.value)} />
          <select className="field" value={asset} onChange={(e) => setAsset(e.target.value)}>
            <option value="ALL" className="bg-obsidian-850">All assets</option>
            {assets.map((a) => <option key={a} value={a} className="bg-obsidian-850">{assetLabel(a)}</option>)}
          </select>
          <select className="field" value={sector} onChange={(e) => setSector(e.target.value)}>
            <option value="ALL" className="bg-obsidian-850">All sectors</option>
            {sectors.map((s) => <option key={s} value={s} className="bg-obsidian-850">{s}</option>)}
          </select>
          <select className="field" value={dir} onChange={(e) => setDir(e.target.value)}>
            <option value="ALL" className="bg-obsidian-850">All movers</option>
            <option value="GAINERS" className="bg-obsidian-850">▲ Gainers</option>
            <option value="LOSERS" className="bg-obsidian-850">▼ Losers</option>
          </select>
          <input className="field" placeholder="Min price" type="number" value={minPrice} onChange={(e) => setMinPrice(e.target.value)} />
          <input className="field" placeholder="Max price" type="number" value={maxPrice} onChange={(e) => setMaxPrice(e.target.value)} />
          <select className="field" value={targetList} onChange={(e) => setTargetList(e.target.value)}>
            <option value="" className="bg-obsidian-850">★ → (new list)</option>
            {lists.map((l) => <option key={l.id} value={l.id} className="bg-obsidian-850">★ {l.name}</option>)}
          </select>
        </div>
        <div className="mt-2 text-[11px] text-ink-500">{filtered.length} of {rows.filter((r) => r.assetClass !== "INDEX").length} instruments</div>
      </div>

      {/* results */}
      <div className="glass overflow-hidden">
        <div className="overflow-auto">
          <table className="w-full text-[12px]">
            <thead className="sticky top-0 bg-obsidian-850/95 text-[10px] uppercase tracking-wider text-ink-600">
              <tr className="text-left">
                {[["symbol", "Symbol"], ["sector", "Sector"], ["assetClass", "Asset"]].map(([k, l]) => (
                  <th key={k} className="cursor-pointer px-3 py-2 hover:text-ink-200" onClick={() => sortBy(k as keyof Row)}>{l}{arrow(k as keyof Row)}</th>
                ))}
                {[["ltp", "LTP"], ["changePct", "Chg%"], ["volume", "Volume"], ["valueMn", "Turnover"], ["high", "High"], ["low", "Low"]].map(([k, l]) => (
                  <th key={k} className="cursor-pointer px-3 py-2 text-right hover:text-ink-200" onClick={() => sortBy(k as keyof Row)}>{l}{arrow(k as keyof Row)}</th>
                ))}
                <th className="px-3 py-2 text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
                <tr key={r.securityId} className="border-t border-line/[0.1] hover:bg-surface/[0.05]">
                  <td className="px-3 py-2 font-semibold text-ink-100">{r.symbol}</td>
                  <td className="px-3 py-2 text-ink-400">{r.sector || "—"}</td>
                  <td className="px-3 py-2"><span className="chip bg-surface/[0.1] text-ink-500">{assetLabel(r.assetClass)}</span></td>
                  <td className="px-3 py-2 text-right tnum text-ink-200">{nf(r.ltp)}</td>
                  <td className={`px-3 py-2 text-right tnum font-semibold ${dirColor(r.changePct)}`}>{pct(r.changePct)}</td>
                  <td className="px-3 py-2 text-right tnum text-ink-300">{compact(r.volume)}</td>
                  <td className="px-3 py-2 text-right tnum text-ink-300">{compact(r.valueMn)}</td>
                  <td className="px-3 py-2 text-right tnum text-ink-400">{nf(r.high)}</td>
                  <td className="px-3 py-2 text-right tnum text-ink-400">{nf(r.low)}</td>
                  <td className="px-3 py-2 text-right whitespace-nowrap">
                    <button onClick={() => star(r)} title="Add to watchlist"
                      className={`mr-1 rounded-md px-2 py-0.5 text-[12px] ${inList(r.symbol) ? "text-amber-300" : "text-ink-500 hover:text-amber-300"}`}>★</button>
                    <button onClick={() => trade(r)} className="rounded-md bg-aurora-indigo/15 px-2 py-0.5 text-[11px] font-medium text-aurora-cyan hover:bg-aurora-indigo/25">Trade ↗</button>
                  </td>
                </tr>
              ))}
              {filtered.length === 0 && <tr><td colSpan={10} className="px-3 py-10 text-center text-ink-600">No matches — adjust filters.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
    </Shell>
  );
}
