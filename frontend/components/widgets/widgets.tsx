"use client";

import {
  ResponsiveContainer, BarChart, Bar, XAxis, YAxis, Tooltip, Cell,
  PieChart, Pie, RadialBarChart, RadialBar, Treemap,
} from "recharts";
import { useDash, Row } from "@/lib/dashboardData";
import { EquityCurve } from "@/components/EquityCurve";
import { nf, compact, pct, dirColor, money } from "@/lib/format";

export function PnLTrend() {
  const { accountId } = useDash();
  return <EquityCurve accountId={accountId} />;
}

const PALETTE = ["#8b5cf6", "#22d3ee", "#2dd4bf", "#6366f1", "#f59e0b", "#fb5b6b", "#a3e635", "#e879f9", "#38bdf8", "#94a3b8"];
const UP = "#22c55e", DOWN = "#fb5b6b";
const tick = { fontSize: 10, fill: "#6b7494" };
const tip = { background: "rgba(14,17,30,0.95)", border: "1px solid rgba(255,255,255,0.1)", borderRadius: 10, fontSize: 12 };

const Box = ({ children }: { children: React.ReactNode }) => <div className="h-full w-full min-h-[140px]">{children}</div>;
const Empty = () => <div className="grid h-full place-items-center text-[12px] text-ink-600">No data yet.</div>;

/* ----------------------------------------------------------------- MARKET */

export function SectorPerformance() {
  const { sectorStats } = useDash();
  const data = sectorStats.map((s) => ({ name: s.sector, v: +s.avgChg.toFixed(2) }));
  if (!data.length) return <Empty />;
  return (
    <Box>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} layout="vertical" margin={{ left: 8, right: 16, top: 4, bottom: 4 }}>
          <XAxis type="number" {...{ tick }} tickLine={false} axisLine={false} unit="%" />
          <YAxis type="category" dataKey="name" width={88} tick={tick} tickLine={false} axisLine={false} />
          <Tooltip contentStyle={tip} formatter={(v: any) => [`${v}%`, "Avg change"]} cursor={{ fill: "rgba(255,255,255,0.04)" }} />
          <Bar dataKey="v" radius={[0, 4, 4, 0]} barSize={12}>
            {data.map((d, i) => <Cell key={i} fill={d.v >= 0 ? UP : DOWN} />)}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </Box>
  );
}

export function MarketBreadth() {
  const { breadth } = useDash();
  const data = [
    { name: "Advancers", value: breadth.up, fill: UP },
    { name: "Decliners", value: breadth.down, fill: DOWN },
    { name: "Unchanged", value: breadth.flat, fill: "#94a3b8" },
  ];
  const total = breadth.up + breadth.down + breadth.flat;
  if (!total) return <Empty />;
  return (
    <Box>
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie data={data} dataKey="value" nameKey="name" innerRadius="55%" outerRadius="80%" paddingAngle={2} stroke="none">
            {data.map((d, i) => <Cell key={i} fill={d.fill} />)}
          </Pie>
          <Tooltip contentStyle={tip} />
        </PieChart>
      </ResponsiveContainer>
      <div className="-mt-6 flex justify-center gap-4 text-[11px]">
        <span className="text-bull">▲ {breadth.up}</span>
        <span className="text-bear">▼ {breadth.down}</span>
        <span className="text-ink-500">▬ {breadth.flat}</span>
      </div>
    </Box>
  );
}

function MoversBar({ rows, dataKey, fmt }: { rows: Row[]; dataKey: "changePct" | "valueMn"; fmt?: (v: number) => string }) {
  const data = rows.map((r) => ({ name: r.symbol, v: +(r as any)[dataKey].toFixed(2) }));
  if (!data.length) return <Empty />;
  return (
    <Box>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ left: -18, right: 8, top: 6, bottom: 4 }}>
          <XAxis dataKey="name" tick={tick} tickLine={false} axisLine={false} interval={0} angle={-30} textAnchor="end" height={42} />
          <YAxis tick={tick} tickLine={false} axisLine={false} width={42} />
          <Tooltip contentStyle={tip} formatter={(v: any) => [fmt ? fmt(v) : `${v}%`, dataKey === "valueMn" ? "Turnover (mn)" : "Change"]} cursor={{ fill: "rgba(255,255,255,0.04)" }} />
          <Bar dataKey="v" radius={[4, 4, 0, 0]} barSize={18}>
            {data.map((d, i) => <Cell key={i} fill={dataKey === "valueMn" ? PALETTE[i % PALETTE.length] : d.v >= 0 ? UP : DOWN} />)}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </Box>
  );
}
export const TopGainers = () => <MoversBar rows={useDash().gainers} dataKey="changePct" />;
export const TopLosers = () => <MoversBar rows={useDash().losers} dataKey="changePct" />;
export const MostActive = () => <MoversBar rows={useDash().active} dataKey="valueMn" fmt={(v) => compact(v) + " mn"} />;

export function TurnoverByAsset() {
  const { assetTurnover } = useDash();
  const data = assetTurnover.map((a, i) => ({ name: a.label.replace(/_/g, " "), size: +a.value.toFixed(2), fill: PALETTE[i % PALETTE.length] }));
  if (!data.length) return <Empty />;
  return (
    <Box>
      <ResponsiveContainer width="100%" height="100%">
        <Treemap data={data} dataKey="size" nameKey="name" stroke="rgba(6,7,13,0.7)" content={<TreeCell />} />
      </ResponsiveContainer>
    </Box>
  );
}
function TreeCell(props: any) {
  const { x, y, width, height, name, fill, value } = props;
  return (
    <g>
      <rect x={x} y={y} width={width} height={height} fill={fill} opacity={0.85} />
      {width > 56 && height > 28 && (
        <text x={x + 6} y={y + 18} fill="#06070d" fontSize={11} fontWeight={700}>{name}</text>
      )}
    </g>
  );
}

export function IndexBoard() {
  const { indices } = useDash();
  if (!indices.length) return <Empty />;
  return (
    <div className="grid grid-cols-2 gap-2">
      {indices.map((i) => (
        <div key={`${i.exchange}-${i.symbol}`} className="glass-soft p-3">
          <div className="flex items-center justify-between">
            <span className="text-[12px] font-semibold text-ink-200">{i.symbol}</span>
            <span className="chip bg-surface/[0.1] text-ink-500">{i.exchange}</span>
          </div>
          <div className="tnum mt-1 text-lg font-bold text-ink-100">{nf(i.ltp, 2)}</div>
          <div className={`tnum text-[12px] font-semibold ${dirColor(i.changePct)}`}>{pct(i.changePct)}</div>
        </div>
      ))}
    </div>
  );
}

export function SectorCrosstab() {
  const { sectorStats } = useDash();
  if (!sectorStats.length) return <Empty />;
  return (
    <table className="w-full text-[12px]">
      <thead className="text-[10px] uppercase tracking-wider text-ink-600">
        <tr className="text-left">
          <th className="px-2 py-1.5">Sector</th>
          <th className="px-2 py-1.5 text-right">Avg %</th>
          <th className="px-2 py-1.5 text-right">Turnover</th>
          <th className="px-2 py-1.5 text-right">Scrips</th>
          <th className="px-2 py-1.5 text-right">Adv</th>
          <th className="px-2 py-1.5 text-right">Dec</th>
        </tr>
      </thead>
      <tbody>
        {sectorStats.map((s) => (
          <tr key={s.sector} className="border-t border-line/[0.1]">
            <td className="px-2 py-1.5 text-ink-200">{s.sector}</td>
            <td className={`px-2 py-1.5 text-right tnum font-semibold ${dirColor(s.avgChg)}`}>{nf(s.avgChg, 2)}</td>
            <td className="px-2 py-1.5 text-right tnum text-ink-300">{compact(s.turnover)}</td>
            <td className="px-2 py-1.5 text-right tnum text-ink-400">{s.count}</td>
            <td className="px-2 py-1.5 text-right tnum text-bull">{s.up}</td>
            <td className="px-2 py-1.5 text-right tnum text-bear">{s.down}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function MarketHeatmap() {
  const { watch } = useDash();
  const eq = watch.filter((r) => r.assetClass !== "INDEX");
  if (!eq.length) return <Empty />;
  const color = (c: number) => {
    const a = Math.min(0.85, Math.abs(c) / 6 + 0.12);
    return c >= 0 ? `rgba(34,197,94,${a})` : `rgba(251,91,107,${a})`;
  };
  return (
    <div className="flex flex-wrap gap-1.5">
      {eq.map((r) => (
        <div key={r.securityId} className="rounded-lg px-2 py-1.5" style={{ background: color(r.changePct), minWidth: 76 }}>
          <div className="text-[11px] font-bold text-white/95">{r.symbol}</div>
          <div className="tnum text-[10px] text-white/85">{pct(r.changePct)}</div>
        </div>
      ))}
    </div>
  );
}

/* -------------------------------------------------------------- PORTFOLIO */

function AllocPie({ slices }: { slices: { label: string; value: number; pct: number }[] }) {
  if (!slices?.length) return <Empty />;
  const data = slices.map((s, i) => ({ name: s.label.replace(/_/g, " "), value: +s.value.toFixed(2), fill: PALETTE[i % PALETTE.length] }));
  return (
    <Box>
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie data={data} dataKey="value" nameKey="name" innerRadius="50%" outerRadius="80%" paddingAngle={2} stroke="none">
            {data.map((d, i) => <Cell key={i} fill={d.fill} />)}
          </Pie>
          <Tooltip contentStyle={tip} formatter={(v: any, n: any) => [compact(v), n]} />
        </PieChart>
      </ResponsiveContainer>
    </Box>
  );
}
export const AllocBySector = () => <AllocPie slices={useDash().portfolio?.bySector} />;
export const AllocByAsset = () => <AllocPie slices={useDash().portfolio?.byAsset} />;

export function PnLByPosition() {
  const { portfolio } = useDash();
  const data = (portfolio?.positions || []).map((p: any) => ({ name: p.symbol, v: +p.unrealizedPnl.toFixed(0) }));
  if (!data.length) return <Empty />;
  return (
    <Box>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ left: -12, right: 8, top: 6, bottom: 4 }}>
          <XAxis dataKey="name" tick={tick} tickLine={false} axisLine={false} interval={0} angle={-30} textAnchor="end" height={42} />
          <YAxis tick={tick} tickLine={false} axisLine={false} width={48} />
          <Tooltip contentStyle={tip} formatter={(v: any) => [money(v), "Unrealized P&L"]} cursor={{ fill: "rgba(255,255,255,0.04)" }} />
          <Bar dataKey="v" radius={[4, 4, 0, 0]} barSize={20}>
            {data.map((d: any, i: number) => <Cell key={i} fill={d.v >= 0 ? UP : DOWN} />)}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </Box>
  );
}

export function PortfolioSummary() {
  const { portfolio: p } = useDash();
  if (!p) return <Empty />;
  const cards = [
    ["Total Value", money(p.totalValue), undefined],
    ["Day P&L", money(p.dayPnl), p.dayPnl],
    ["Unrealized", money(p.unrealizedPnl), p.unrealizedPnl],
    ["Realized", money(p.realizedPnl), p.realizedPnl],
    ["Cash", money(p.cash), undefined],
    ["Invested", money(p.invested), undefined],
  ] as const;
  return (
    <div className="grid grid-cols-2 gap-2">
      {cards.map(([l, v, a]) => (
        <div key={l} className="glass-soft p-2.5">
          <div className="text-[9.5px] uppercase tracking-wider text-ink-600">{l}</div>
          <div className={`tnum text-[15px] font-bold ${a === undefined ? "text-ink-100" : dirColor(a as number)}`}>{v}</div>
        </div>
      ))}
    </div>
  );
}

export function HoldingsGrid() {
  const { portfolio } = useDash();
  const pos = portfolio?.positions || [];
  if (!pos.length) return <Empty />;
  return (
    <table className="w-full text-[12px]">
      <thead className="text-[10px] uppercase tracking-wider text-ink-600">
        <tr className="text-left"><th className="px-2 py-1.5">Symbol</th><th className="px-2 py-1.5 text-right">Qty</th>
          <th className="px-2 py-1.5 text-right">LTP</th><th className="px-2 py-1.5 text-right">P&L</th><th className="px-2 py-1.5 text-right">Wt%</th></tr>
      </thead>
      <tbody>
        {pos.map((p: any) => (
          <tr key={p.securityId} className="border-t border-line/[0.1]">
            <td className="px-2 py-1.5 font-semibold text-ink-100">{p.symbol}</td>
            <td className="px-2 py-1.5 text-right tnum text-ink-300">{compact(p.quantity)}</td>
            <td className="px-2 py-1.5 text-right tnum text-ink-200">{nf(p.ltp)}</td>
            <td className={`px-2 py-1.5 text-right tnum font-semibold ${dirColor(p.unrealizedPnl)}`}>{nf(p.unrealizedPnl, 0)}</td>
            <td className="px-2 py-1.5 text-right tnum text-ink-400">{nf(p.weightPct, 1)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/* ----------------------------------------------------------- ORDERS / RISK */

export function OrderStatusBreakdown() {
  const { orderStatus } = useDash();
  const map: Record<string, string> = { FILLED: UP, PARTIAL: "#22d3ee", OPEN: "#6366f1", REJECTED: DOWN, CANCELLED: "#94a3b8", PENDING_RISK: "#f59e0b" };
  const data = orderStatus.map((s) => ({ name: s.label, value: s.value, fill: map[s.label] || "#8b5cf6" }));
  if (!data.length) return <Empty />;
  return (
    <Box>
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie data={data} dataKey="value" nameKey="name" innerRadius="50%" outerRadius="80%" paddingAngle={2} stroke="none">
            {data.map((d, i) => <Cell key={i} fill={d.fill} />)}
          </Pie>
          <Tooltip contentStyle={tip} />
        </PieChart>
      </ResponsiveContainer>
    </Box>
  );
}

export function RiskDistribution() {
  const { riskBuckets } = useDash();
  const colors = ["#22c55e", "#a3e635", "#f59e0b", "#fb923c", "#fb5b6b"];
  if (!riskBuckets.some((b) => b.value > 0)) return <Empty />;
  return (
    <Box>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={riskBuckets} margin={{ left: -20, right: 8, top: 6, bottom: 4 }}>
          <XAxis dataKey="label" tick={tick} tickLine={false} axisLine={false} />
          <YAxis tick={tick} tickLine={false} axisLine={false} width={32} allowDecimals={false} />
          <Tooltip contentStyle={tip} formatter={(v: any) => [v, "Orders"]} cursor={{ fill: "rgba(255,255,255,0.04)" }} />
          <Bar dataKey="value" radius={[4, 4, 0, 0]} barSize={26}>
            {riskBuckets.map((_, i) => <Cell key={i} fill={colors[i]} />)}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </Box>
  );
}

export function OrderBlotter() {
  const { orders } = useDash();
  const statusCls: Record<string, string> = { FILLED: "text-bull", PARTIAL: "text-aurora-cyan", OPEN: "text-aurora-indigo", REJECTED: "text-bear", CANCELLED: "text-ink-500" };
  if (!orders.length) return <Empty />;
  return (
    <table className="w-full text-[12px]">
      <thead className="text-[10px] uppercase tracking-wider text-ink-600">
        <tr className="text-left"><th className="px-2 py-1.5">Symbol</th><th className="px-2 py-1.5">Side</th>
          <th className="px-2 py-1.5 text-right">Qty</th><th className="px-2 py-1.5 text-right">Px</th><th className="px-2 py-1.5">Status</th></tr>
      </thead>
      <tbody>
        {orders.slice(0, 40).map((o) => (
          <tr key={o.id} className="border-t border-line/[0.1]">
            <td className="px-2 py-1.5 font-semibold text-ink-100">{o.symbol}</td>
            <td className={`px-2 py-1.5 font-bold ${o.side === "BUY" ? "text-bull" : "text-bear"}`}>{o.side}</td>
            <td className="px-2 py-1.5 text-right tnum text-ink-300">{compact(o.quantity)}</td>
            <td className="px-2 py-1.5 text-right tnum text-ink-300">{o.orderType === "MARKET" ? "MKT" : nf(o.price)}</td>
            <td className={`px-2 py-1.5 font-medium ${statusCls[o.status] || "text-ink-400"}`}>{o.status}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function RiskAlerts() {
  const { orders } = useDash();
  const flagged = orders.filter((o) => o.status === "REJECTED" || (o.riskScore || 0) >= 40).slice(0, 30);
  if (!flagged.length) return <div className="grid h-full place-items-center text-[12px] text-ink-600">No elevated-risk orders. All clear.</div>;
  return (
    <div className="space-y-1.5">
      {flagged.map((o) => (
        <div key={o.id} className="rounded-lg border border-line/[0.1] bg-obsidian-900/50 px-3 py-2">
          <div className="flex items-center justify-between text-[12px]">
            <span><span className={`font-bold ${o.side === "BUY" ? "text-bull" : "text-bear"}`}>{o.side}</span> <span className="font-semibold text-ink-100">{o.symbol}</span></span>
            <span className="tnum text-[11px] font-semibold" style={{ color: o.riskScore >= 60 ? DOWN : o.riskScore >= 40 ? "#f59e0b" : UP }}>{Math.round(o.riskScore)}</span>
          </div>
          {o.rejectReason && <div className="text-[11px] text-bear">{o.rejectReason}</div>}
        </div>
      ))}
    </div>
  );
}

export function NewsFeed() {
  const { news } = useDash();
  if (!news.length) return <Empty />;
  const senti: Record<string, string> = { POSITIVE: "text-bull", NEGATIVE: "text-bear", NEUTRAL: "text-ink-400" };
  return (
    <div className="space-y-2">
      {news.map((n) => (
        <div key={n.id} className="rounded-lg border border-line/[0.1] bg-surface/[0.05] p-2.5">
          <div className="flex items-center gap-2">
            <span className="chip bg-surface/[0.1] text-ink-500">{n.category}</span>
            {n.symbol && <span className="text-[11px] font-semibold text-aurora-cyan">{n.symbol}</span>}
            {n.sentiment && <span className={`ml-auto text-[10px] font-semibold ${senti[n.sentiment] || ""}`}>{n.sentiment}</span>}
          </div>
          <div className="mt-1 text-[12px] font-medium text-ink-200">{n.title}</div>
        </div>
      ))}
    </div>
  );
}
