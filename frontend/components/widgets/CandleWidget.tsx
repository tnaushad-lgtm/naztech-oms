"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useDash } from "@/lib/dashboardData";
import { API } from "@/lib/api";

const TFS = ["1m", "5m", "15m", "1d"];

export function CandleWidget() {
  const { watch } = useDash();
  const equities = useMemo(() => watch.filter((r) => r.assetClass !== "INDEX"), [watch]);
  const [symbol, setSymbol] = useState<string>("");
  const [tf, setTf] = useState("5m");
  const wrapRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<HTMLDivElement>(null);

  // default the symbol once data arrives
  useEffect(() => { if (!symbol && equities.length) setSymbol(equities[0].symbol); }, [equities, symbol]);
  const sec = equities.find((e) => e.symbol === symbol);

  useEffect(() => {
    if (!sec) return;
    let chart: any, ro: ResizeObserver | null = null, disposed = false;
    (async () => {
      const lib = await import("lightweight-charts");
      if (disposed || !chartRef.current) return;
      let candles: any[] = [];
      try { candles = await (await fetch(`${API}/api/market/${sec.securityId}/candles?tf=${tf}&limit=140`)).json(); } catch {}
      if (!Array.isArray(candles) || !candles.length) return;
      chartRef.current.innerHTML = "";
      const h = wrapRef.current ? Math.max(140, wrapRef.current.clientHeight - 34) : 200;
      chart = lib.createChart(chartRef.current, {
        layout: { background: { type: lib.ColorType.Solid, color: "transparent" }, textColor: "#9aa3c4", fontSize: 10 },
        grid: { vertLines: { color: "rgba(148,163,184,0.05)" }, horzLines: { color: "rgba(148,163,184,0.06)" } },
        rightPriceScale: { borderColor: "rgba(148,163,184,0.10)" },
        timeScale: { borderColor: "rgba(148,163,184,0.10)", timeVisible: tf !== "1d", secondsVisible: false },
        height: h,
      });
      const cs = chart.addCandlestickSeries({ upColor: "#22c55e", downColor: "#fb5b6b", borderVisible: false, wickUpColor: "#22c55e", wickDownColor: "#fb5b6b" });
      cs.setData(candles.map((c) => ({ time: c.time, open: c.open, high: c.high, low: c.low, close: c.close })));
      const vs = chart.addHistogramSeries({ priceFormat: { type: "volume" }, priceScaleId: "" });
      vs.priceScale().applyOptions({ scaleMargins: { top: 0.84, bottom: 0 } });
      vs.setData(candles.map((c) => ({ time: c.time, value: c.volume, color: c.close >= c.open ? "rgba(34,197,94,0.4)" : "rgba(251,91,107,0.4)" })));
      chart.timeScale().fitContent();
      const resize = () => {
        if (!chartRef.current || !wrapRef.current) return;
        chart.applyOptions({ width: chartRef.current.clientWidth, height: Math.max(140, wrapRef.current.clientHeight - 34) });
      };
      resize();
      ro = new ResizeObserver(resize); if (wrapRef.current) ro.observe(wrapRef.current);
    })();
    return () => { disposed = true; ro?.disconnect(); chart?.remove?.(); };
  }, [sec?.securityId, tf]);

  return (
    <div ref={wrapRef} className="flex h-full flex-col">
      <div className="mb-1 flex items-center justify-between gap-2">
        <select className="field max-w-[55%] py-1 text-[11px]" value={symbol} onChange={(e) => setSymbol(e.target.value)}>
          {equities.map((e) => <option key={e.securityId} value={e.symbol} className="bg-obsidian-850">{e.symbol}</option>)}
        </select>
        <div className="flex rounded-lg border border-line/[0.12] bg-surface/[0.05] p-0.5">
          {TFS.map((x) => (
            <button key={x} onClick={() => setTf(x)}
              className={`rounded-md px-2 py-0.5 text-[10px] font-semibold ${tf === x ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white" : "text-ink-400 hover:text-ink-100"}`}>
              {x.toUpperCase()}
            </button>
          ))}
        </div>
      </div>
      <div ref={chartRef} className="min-h-0 flex-1" />
    </div>
  );
}
