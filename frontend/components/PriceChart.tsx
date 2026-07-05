"use client";

import { useEffect, useRef, useState } from "react";
import { API, feedForecast } from "@/lib/api";
import { nf } from "@/lib/format";

const TFS = [
  { k: "1m", label: "1m" }, { k: "5m", label: "5m" }, { k: "15m", label: "15m" }, { k: "1d", label: "1D" },
];

export function PriceChart({ securityId, symbol, exchange, ltp, height = 300 }: { securityId: number; symbol: string; exchange: string; ltp: number; height?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const [tf, setTf] = useState("5m");
  const [outlook, setOutlook] = useState<{ trend: string; r2: number } | null>(null);

  // AI outlook badge (independent of candle timeframe)
  useEffect(() => {
    let on = true;
    feedForecast(symbol, exchange, 5).then((f) => {
      if (on && f) setOutlook({ trend: f.trend || "FLAT", r2: f.confidenceR2 ?? 0 });
      else if (on) setOutlook(null);
    });
    return () => { on = false; };
  }, [symbol, exchange]);

  useEffect(() => {
    let chart: any, ro: ResizeObserver | null = null, disposed = false;
    (async () => {
      const lib = await import("lightweight-charts");
      if (disposed || !ref.current) return;
      let candles: any[] = [];
      try {
        const res = await fetch(`${API}/api/market/${securityId}/candles?tf=${tf}&limit=160`);
        candles = await res.json();
      } catch { candles = []; }
      if (!Array.isArray(candles) || !candles.length) return;

      ref.current.innerHTML = "";
      chart = lib.createChart(ref.current, {
        layout: { background: { type: lib.ColorType.Solid, color: "transparent" }, textColor: "#9aa3c4", fontSize: 11 },
        grid: { vertLines: { color: "rgba(148,163,184,0.05)" }, horzLines: { color: "rgba(148,163,184,0.06)" } },
        rightPriceScale: { borderColor: "rgba(148,163,184,0.10)" },
        timeScale: { borderColor: "rgba(148,163,184,0.10)", timeVisible: tf !== "1d", secondsVisible: false },
        crosshair: { mode: lib.CrosshairMode.Normal, vertLine: { color: "rgba(139,92,246,0.4)" }, horzLine: { color: "rgba(34,211,238,0.4)" } },
        height,
      });
      const cs = chart.addCandlestickSeries({
        upColor: "#22c55e", downColor: "#fb5b6b", borderVisible: false,
        wickUpColor: "#22c55e", wickDownColor: "#fb5b6b",
      });
      cs.setData(candles.map((c) => ({ time: c.time, open: c.open, high: c.high, low: c.low, close: c.close })));
      const vs = chart.addHistogramSeries({ priceFormat: { type: "volume" }, priceScaleId: "" });
      vs.priceScale().applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });
      vs.setData(candles.map((c) => ({
        time: c.time, value: c.volume,
        color: c.close >= c.open ? "rgba(34,197,94,0.45)" : "rgba(251,91,107,0.45)",
      })));
      chart.timeScale().fitContent();
      const resize = () => { if (ref.current) chart.applyOptions({ width: ref.current.clientWidth }); };
      resize();
      ro = new ResizeObserver(resize); ro.observe(ref.current);
    })();
    return () => { disposed = true; ro?.disconnect(); chart?.remove?.(); };
  }, [securityId, tf]);

  return (
    <div className="glass p-4">
      <div className="mb-2 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="panel-title">Price · Volume</div>
          {outlook && (
            <span className={`chip ${outlook.trend === "UP" ? "bg-bull/15 text-bull" : outlook.trend === "DOWN" ? "bg-bear/15 text-bear" : "bg-surface/[0.1] text-ink-400"}`}>
              AI {outlook.trend === "UP" ? "▲" : outlook.trend === "DOWN" ? "▼" : "▬"} {outlook.trend} · {nf((outlook.r2 || 0) * 100, 0)}%
            </span>
          )}
        </div>
        <div className="flex rounded-lg border border-line/[0.12] bg-surface/[0.05] p-0.5">
          {TFS.map((x) => (
            <button key={x.k} onClick={() => setTf(x.k)}
              className={`rounded-md px-2.5 py-1 text-[11px] font-semibold transition-all ${tf === x.k ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white" : "text-ink-400 hover:text-ink-100"}`}>
              {x.label}
            </button>
          ))}
        </div>
      </div>
      <div ref={ref} className="w-full" style={{ height }} />
      <div className="mt-1 text-[10px] text-ink-600">
        Candles &amp; volume aggregated from the live matching-engine tape · {tf.toUpperCase()} buckets
      </div>
    </div>
  );
}
