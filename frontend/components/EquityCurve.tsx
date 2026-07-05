"use client";

import { useEffect, useState } from "react";
import { ResponsiveContainer, AreaChart, Area, XAxis, YAxis, Tooltip, ReferenceLine } from "recharts";
import { get } from "@/lib/api";
import { compact, money } from "@/lib/format";

type Point = { time: number; totalValue: number; unrealizedPnl: number; realizedPnl: number; pnl: number; dayPnl: number };

export function EquityCurve({ accountId }: { accountId: number | null }) {
  const [data, setData] = useState<Point[]>([]);
  const [metric, setMetric] = useState<"pnl" | "totalValue">("pnl");

  useEffect(() => {
    if (!accountId) return;
    let stop = false;
    const load = async () => { try { const d = await get<Point[]>(`/api/portfolio/${accountId}/equity?limit=160`); if (!stop) setData(d); } catch {} };
    load(); const t = setInterval(load, 15000);
    return () => { stop = true; clearInterval(t); };
  }, [accountId]);

  const last = data.length ? (data[data.length - 1] as any)[metric] : 0;
  const up = last >= 0 || metric === "totalValue";
  const color = metric === "totalValue" ? "#8b5cf6" : up ? "#22c55e" : "#fb5b6b";
  const fmtDate = (t: number) => new Date(t * 1000).toLocaleDateString("en-GB", { day: "2-digit", month: "short" });

  if (!data.length) return <div className="grid h-full place-items-center text-[12px] text-ink-600">Building equity history…</div>;

  return (
    <div className="flex h-full flex-col">
      <div className="mb-1 flex items-center justify-between">
        <div className="flex gap-2 text-[12px]">
          <span className="text-ink-500">Latest</span>
          <span className="tnum font-semibold" style={{ color }}>
            {metric === "totalValue" ? money(last) : `${last >= 0 ? "+" : ""}${money(last)}`}
          </span>
        </div>
        <div className="flex rounded-lg border border-line/[0.12] bg-surface/[0.05] p-0.5">
          {(["pnl", "totalValue"] as const).map((m) => (
            <button key={m} onClick={() => setMetric(m)}
              className={`rounded-md px-2 py-0.5 text-[10px] font-semibold ${metric === m ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white" : "text-ink-400 hover:text-ink-100"}`}>
              {m === "pnl" ? "P&L" : "Equity"}
            </button>
          ))}
        </div>
      </div>
      <div className="min-h-0 flex-1">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data} margin={{ top: 6, right: 6, left: -8, bottom: 0 }}>
            <defs>
              <linearGradient id={`eq-${accountId}-${metric}`} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={color} stopOpacity={0.4} />
                <stop offset="100%" stopColor={color} stopOpacity={0.02} />
              </linearGradient>
            </defs>
            <XAxis dataKey="time" tickFormatter={fmtDate} tick={{ fontSize: 10, fill: "#6b7494" }} tickLine={false} axisLine={false} minTickGap={40} />
            <YAxis tickFormatter={(v) => compact(v)} tick={{ fontSize: 10, fill: "#6b7494" }} tickLine={false} axisLine={false} width={44} />
            {metric === "pnl" && <ReferenceLine y={0} stroke="rgba(148,163,184,0.3)" strokeDasharray="3 3" />}
            <Tooltip contentStyle={{ background: "rgba(14,17,30,0.95)", border: "1px solid rgba(255,255,255,0.1)", borderRadius: 10, fontSize: 12 }}
              labelFormatter={(t) => new Date((t as number) * 1000).toLocaleString("en-GB")}
              formatter={(v: any) => [money(v), metric === "pnl" ? "Cumulative P&L" : "Equity"]} />
            <Area type="monotone" dataKey={metric} stroke={color} strokeWidth={2} fill={`url(#eq-${accountId}-${metric})`} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
